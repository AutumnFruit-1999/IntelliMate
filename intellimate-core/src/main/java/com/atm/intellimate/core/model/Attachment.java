package com.atm.intellimate.core.model;

/**
 * Represents a file or media attachment within a message.
 *
 * @param type     MIME type (e.g. "image/png", "application/pdf")
 * @param url      accessible URL or local path to the attachment
 * @param fileName original file name
 * @param size     file size in bytes, nullable
 */
public record Attachment(
        String type,
        String url,
        String fileName,
        Long size
) {
}
