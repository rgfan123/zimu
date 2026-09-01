package cn.zimu.fulfillment.file;

import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

/** Excel 单元格边界：计数读取底层数值，不能让显示格式先行舍入。 */
public final class ExcelCellValues {

    private ExcelCellValues() {}

    public static String exactCount(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type != CellType.NUMERIC || DateUtil.isCellDateFormatted(cell)) {
            return formatter.formatCellValue(cell);
        }
        double value = cell.getNumericCellValue();
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
