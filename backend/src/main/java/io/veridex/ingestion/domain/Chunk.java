package io.veridex.ingestion.domain;

public record Chunk(int index, String text, String title, String structurePath) {
}
