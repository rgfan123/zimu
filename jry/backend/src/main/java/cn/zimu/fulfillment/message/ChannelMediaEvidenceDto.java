package cn.zimu.fulfillment.message;

/**
 * Whitelisted media evidence reference for the review UI.
 *
 * <p>Never exposes {@code content_ref} (storage path), {@code decrypt_info} (AES key) or the raw
 * protocol URL: the original bytes are only reachable through the authorized
 * {@code /api/v1/message-media/{id}/content} endpoint.
 */
public record ChannelMediaEvidenceDto(
        long id,
        String mediaType,
        String contentType,
        Long sizeBytes) {}
