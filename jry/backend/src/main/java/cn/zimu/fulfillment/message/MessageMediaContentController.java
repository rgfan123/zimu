package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.message.MessageMediaContentService.MediaContent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 复核页原图受权接口：返回解密后的媒体证据字节（受 Basic Auth + X-Operator 全局校验保护）。
 * 不暴露磁盘路径、下载凭据或 aeskey。
 */
@RestController
class MessageMediaContentController {

    private final MessageMediaContentService contentService;

    MessageMediaContentController(MessageMediaContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/api/v1/message-media/{id}/content")
    ResponseEntity<byte[]> content(@PathVariable long id) {
        MediaContent content = contentService.load(id);
        String contentType = content.contentType() == null || content.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : content.contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(content.bytes());
    }
}
