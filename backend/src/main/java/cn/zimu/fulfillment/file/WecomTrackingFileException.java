package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;

/** 文件任务的无敏感信息控制流异常。 */
final class WecomTrackingFileException extends RuntimeException {

    private final WecomTrackingFileFailureCode code;

    WecomTrackingFileException(WecomTrackingFileFailureCode code) {
        super(null, null, false, false);
        this.code = code;
    }

    WecomTrackingFileFailureCode code() {
        return code;
    }
}
