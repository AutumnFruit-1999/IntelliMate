package com.atm.intellimate.agent.model;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

public class DelegatingEmbeddingModel implements EmbeddingModel {

    private volatile EmbeddingModel delegate;

    public DelegatingEmbeddingModel() {
    }

    public DelegatingEmbeddingModel(EmbeddingModel initial) {
        this.delegate = initial;
    }

    public void setDelegate(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    public boolean isInitialized() {
        return delegate != null;
    }

    public EmbeddingModel getDelegate() {
        return delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return requireDelegate().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return requireDelegate().embed(document);
    }

    @Override
    public float[] embed(String text) {
        return requireDelegate().embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return requireDelegate().embed(texts);
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        return requireDelegate().embedForResponse(texts);
    }

    @Override
    public int dimensions() {
        return requireDelegate().dimensions();
    }

    private EmbeddingModel requireDelegate() {
        EmbeddingModel d = delegate;
        if (d == null) {
            throw new IllegalStateException(
                    "EmbeddingModel not initialized. Configure an embedding model in the model management page.");
        }
        return d;
    }
}
