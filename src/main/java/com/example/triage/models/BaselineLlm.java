package com.example.triage.models;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A deterministic, offline stand-in for an LLM, implemented against ADK's {@link BaseLlm}
 * contract. ADK cannot tell it apart from Gemini: it receives the same {@link LlmRequest}
 * (history, system instruction, tool declarations) and returns tool calls or text.
 *
 * <p>Uses: run everything without an API key, keep CI deterministic, and load-test the serving
 * layer without paying for tokens ({@code simulatedLatencyMs} mimics model latency).
 */
public final class BaselineLlm extends BaseLlm {

  private final AgentPolicy policy;
  private final long simulatedLatencyMs;

  public BaselineLlm(String agentName, AgentPolicy policy, long simulatedLatencyMs) {
    super("baseline/" + agentName);
    this.policy = policy;
    this.simulatedLatencyMs = simulatedLatencyMs;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    Flowable<LlmResponse> response =
        Flowable.fromCallable(() -> toResponse(policy.decide(toPolicyInput(request))));
    return simulatedLatencyMs > 0
        ? response.delaySubscription(simulatedLatencyMs, TimeUnit.MILLISECONDS)
        : response;
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException("The baseline model does not support live sessions");
  }

  static PolicyInput toPolicyInput(LlmRequest request) {
    String incident = "";
    List<PolicyInput.ToolResult> toolResults = new ArrayList<>();
    for (Content content : request.contents()) {
      boolean fromUser = "user".equals(content.role().orElse(""));
      for (Part part : content.parts().orElse(List.of())) {
        String text = part.text().orElse("");
        // The first user text is the incident. ADK relays other agents' turns as user-role
        // messages that start with "For context:", so skip those.
        if (incident.isEmpty() && fromUser && !text.isBlank() && !text.startsWith("For context:")) {
          incident = text;
        }
        part.functionResponse()
            .ifPresent(
                fr ->
                    toolResults.add(
                        new PolicyInput.ToolResult(
                            fr.name().orElse(""), fr.response().orElse(Map.of()))));
      }
    }
    return new PolicyInput(
        incident, String.join("\n", request.getSystemInstructions()), toolResults);
  }

  static LlmResponse toResponse(Decision decision) {
    Part part =
        switch (decision) {
          case Decision.CallTool call -> Part.fromFunctionCall(call.tool(), call.args());
          case Decision.Respond respond -> Part.fromText(respond.text());
        };
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(List.of(part)).build())
        .build();
  }
}
