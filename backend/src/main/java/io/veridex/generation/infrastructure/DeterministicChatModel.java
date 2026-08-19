package io.veridex.generation.infrastructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 确定性 ChatModel（Spring AI ChatModel 实现）：从 system 消息的证据块
 * （[EVIDENCE {index}|{title}|{text}]）生成模板化回答。无外部依赖、测试可复现，
 * 用于验证检索→生成→引用校验链路；真实模型 = 新增 provider 实现切换配置。
 */
@Component
@ConditionalOnProperty(name = "veridex.chat.provider", havingValue = "deterministic", matchIfMissing = true)
public class DeterministicChatModel implements ChatModel {

    private static final Pattern EVIDENCE = Pattern.compile("\\[EVIDENCE (\\d+)\\|([^|]+)\\|([^\\]]+)\\]");
    private static final int STREAM_CHUNK = 8;

    @Override
    public ChatResponse call(Prompt prompt) {
        String answer = answerFor(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String answer = answerFor(prompt);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += STREAM_CHUNK) {
            parts.add(answer.substring(i, Math.min(answer.length(), i + STREAM_CHUNK)));
        }
        return Flux.fromIterable(parts)
                .map(part -> new ChatResponse(List.of(new Generation(new AssistantMessage(part)))));
    }

    private String answerFor(Prompt prompt) {
        String system = prompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(Message::getText)
                .reduce("", (a, b) -> a + b);
        String question = prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((a, b) -> b)
                .map(Message::getText)
                .orElse("");

        Matcher matcher = EVIDENCE.matcher(system);
        List<String[]> evidence = new ArrayList<>();
        while (matcher.find()) {
            evidence.add(new String[] {matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        if (evidence.isEmpty()) {
            return "REFUSE:NO_RELEVANT_EVIDENCE";
        }

        // 选与问题共享字符最多的证据段（确定性模型下的简单相关性近似）
        String[] best = evidence.stream()
                .max(Comparator.comparingLong(e -> Arrays.stream(e[2].split(""))
                        .filter(question::contains)
                        .count()))
                .orElse(evidence.get(0));
        return "根据《" + best[1] + "》[" + best[0] + "]，" + best[2];
    }
}
