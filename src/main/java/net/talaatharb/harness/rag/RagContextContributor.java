package net.talaatharb.harness.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.context.ContextContributor;

/** Adds the passages of this conversation's documents most relevant to the latest user message. */
public class RagContextContributor implements ContextContributor {
    public static final String ID = "rag";
    private static final Logger LOG = LoggerFactory.getLogger(RagContextContributor.class);

    private final RagService rag;

    @Inject
    public RagContextContributor(RagService rag) {
        this.rag = rag;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Passages from this conversation's documents relevant to the latest message (📚, RAG tab)";
    }

    @Override
    public int order() {
        return 160;
    }

    @Override
    public void contribute(ContextAssembly a) {
        RagSettings settings = a.settings().rag == null ? new RagSettings() : a.settings().rag;
        String conversation = a.conversationId();
        String message = a.latestUserMessage();
        if (!settings.autoContext || settings.contextResults <= 0 || conversation == null || message == null
                || message.isBlank()) {
            return;
        }
        RagService.SearchResult result;
        try {
            if (rag.documents(conversation).isEmpty()) {
                return;
            }
            result = rag.search(conversation, message, settings.contextResults, settings.minScorePercent / 100.0);
        } catch (Exception e) {
            LOG.warn("Document retrieval failed for conversation {}: {}", conversation, e.getMessage());
            a.addSystemSection("## Documents\nThis conversation has documents, but retrieving passages failed ("
                    + e.getMessage() + "). If the answer depends on them, tell the user.");
            return;
        }
        StringBuilder sb = new StringBuilder("## Documents\nPassages from the ").append(result.documents())
                .append(" document(s) in this conversation's knowledge base, most relevant first. Use them when they"
                        + " answer the question and name the document you used; search for more with "
                        + RagSearchTool.NAME + ".");
        if (result.hits().isEmpty()) {
            sb.append("\nNo passage matched the latest message closely.");
        }
        int budget = Math.max(500, settings.maxContextChars);
        int i = 1;
        for (RagService.Hit hit : result.hits()) {
            String text = hit.text();
            if (text.length() > budget) {
                if (i > 1) {
                    break;
                }
                text = text.substring(0, budget) + "…";
            }
            budget -= text.length();
            sb.append("\n\n[").append(i++).append("] ").append(RagService.label(hit)).append('\n').append(text);
        }
        if (!result.otherEmbedder().isEmpty()) {
            sb.append("\n\n(").append(result.otherEmbedder().size())
                    .append(" document(s) need re-indexing with the current embedding model and were not searched.)");
        }
        a.addSystemSection(sb.toString());
    }
}
