package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.util.List;

record ParsedSourceFile(
        SourceChannel sourceChannel,
        String templateFamily,
        String templateVersion,
        String templateFingerprint,
        boolean csv,
        List<ParsedSourceRow> rows) {}
