package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.generation.infrastructure.DeterministicChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class DeterministicChatModelTest {

    private final DeterministicChatModel model = new DeterministicChatModel();

    private Prompt promptWithEvidence(String question, List<String> evidence) {
        String system = "你是企业制度问答助手，只能使用给定证据回答。\n" +
                evidence.stream().map(e -> "[EVIDENCE " + e + "]").reduce("", (a, b) -> a + b + "\n");
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(question)));
    }

    @Test
    void callGeneratesCitingAnswerFromEvidence() {
        var prompt = promptWithEvidence("请假几天", List.of("1|请假制度|员工请假需提前两个工作日申请"));
        ChatResponse response = model.call(prompt);
        String answer = response.getResult().getOutput().getText();
        assertThat(answer).contains("[1]").contains("请假");
    }

    @Test
    void callRefusesWhenNoEvidence() {
        var prompt = new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("你好")));
        ChatResponse response = model.call(prompt);
        assertThat(response.getResult().getOutput().getText()).startsWith("REFUSE:NO_RELEVANT_EVIDENCE");
    }

    @Test
    void streamEmitsDeltaChunksAndEnds() {
        var prompt = promptWithEvidence("年假", List.of("1|请假制度|年假最长不超过十五个工作日"));
        List<String> deltas = model.stream(prompt).map(r -> r.getResult().getOutput().getText())
                .collectList().block();
        assertThat(deltas).isNotEmpty();
        assertThat(String.join("", deltas)).contains("[1]");
    }
}
