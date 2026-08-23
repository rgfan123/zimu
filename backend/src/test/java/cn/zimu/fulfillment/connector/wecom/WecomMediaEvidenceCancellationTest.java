package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.MessageMediaStore;
import cn.zimu.fulfillment.message.MessageMediaStore.MediaState;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WecomMediaEvidenceCancellationTest {

    @Test
    void interruptedDownloadPropagatesCancellationWithoutRecordingMediaFailure() {
        WecomMediaDownloader downloader = mock(WecomMediaDownloader.class);
        WecomMediaCrypto crypto = mock(WecomMediaCrypto.class);
        WecomMediaFileStore fileStore = mock(WecomMediaFileStore.class);
        MessageMediaStore mediaStore = mock(MessageMediaStore.class);
        when(mediaStore.ensurePending(11L, 12L, "file-0", "file", "https://media.example/file"))
                .thenReturn(13L);
        when(mediaStore.find(11L, "file-0"))
                .thenReturn(Optional.of(new MediaState(13L, "PENDING", null, null, null, null)));
        when(mediaStore.recordFailure(anyLong(), anyString(), anyInt())).thenReturn("PENDING");
        when(downloader.download("https://media.example/file", WecomMediaEvidenceService.MAX_MEDIA_BYTES))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    throw new WecomMediaDownloader.MediaDownloadException("媒体下载被中断");
                });
        WecomMediaEvidenceService service =
                new WecomMediaEvidenceService(downloader, crypto, fileStore, mediaStore);
        MediaEvidenceCommand command = new MediaEvidenceCommand(
                11L,
                12L,
                "file-0",
                "file",
                "https://media.example/file",
                "test-aes-key");

        try {
            assertThatThrownBy(() -> service.storeMedia(command))
                    .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                    .hasMessageContaining("中断");
            verify(mediaStore, never()).recordFailure(anyLong(), anyString(), anyInt());
        } finally {
            Thread.interrupted();
        }
    }
}
