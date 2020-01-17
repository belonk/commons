package com.belonk.msoffice.excel;

import com.belonk.commons.util.Converter;
import com.belonk.commons.util.asserts.Assert;
import com.belonk.commons.util.clazz.ReflectUtil;
import com.belonk.commons.util.date.DateHelper;
import com.belonk.commons.util.string.StringHelper;
import com.belonk.msoffice.annotation.Excel;
import com.belonk.msoffice.annotation.Excel.Type;
import com.belonk.msoffice.annotation.Excels;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFDateUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

import static com.belonk.msoffice.annotation.Excel.ColumnType;

/**
 * Excel处理器
 *
 * @author sun
 */
public class ExcelProcessor<T> {
    private static final Logger log = LoggerFactory.getLogger(ExcelProcessor.class);

    private ExcelConfig excelConfig = new ExcelConfig();

    private int maxRows;

    private int titleRowStart;

    private int dataRowStart = titleRowStart + 1;

    /**
     * Excel文件名称
     */
    private String fileName;

    /**
     * 工作表名称
     */
    private String sheetName;

    /**
     * 导出类型（EXPORT:导出数据；IMPORT：导入模板）
     */
    private Type type = Type.EXPORT;

    /**
     * 工作薄对象
     */
    private Workbook wb;

    /**
     * 工作表对象
     */
    private Sheet sheet;

    /**
     * 样式列表
     */
    private Map<String, CellStyle> styles;

    /**
     * 导入导出数据列表
     */
    private List<T> list;

    /**
     * 注解列表
     */
    private List<Object[]> fields;

    /**
     * 实体对象
     */
    private Class<T> clazz;

    public ExcelProcessor() {
    }

    public ExcelProcessor(ExcelConfig excelConfig) {
        if (excelConfig != null) {
            this.excelConfig = excelConfig;
            this.maxRows = excelConfig.getMaxRows();
            this.titleRowStart = excelConfig.getTitleRowStart();
            this.dataRowStart = excelConfig.getDataRowStart();
        }
    }

    private void init(String fileName, String sheetName, Class<T> clazz, List<T> list, Type type) {
        Assert.notNull(clazz, "Class<T> must not be null, please invoke setClazz() method at first.");
        Assert.hasLength(fileName, "Filename must not be empty.");
        Assert.hasLength(sheetName, "SheetName must not be empty.");
        Assert.isTrue(this.maxRows > 0, "Max rows number per sheet must be great then 0.");
        if (list == null) {
            list = new ArrayList<T>();
        }
        this.fileName = fileName;
        this.sheetName = sheetName;
        this.list = list;
        this.clazz = clazz;
        this.type = type;
        createExcelField();
        createWorkbook();
    }

    /**
     * 对excel表单默认第一个索引名转换成list
     *
     * @param is    输入流
     * @param clazz 实体类
     * @return 转换后集合
     */
    public List<T> importExcel(InputStream is, Class<T> clazz) throws Exception {
        return importExcel(StringUtils.EMPTY, is, clazz);
    }

    /**
     * 对excel表单指定表格索引名转换成list
     *
     * @param sheetName 表格索引名
     * @param is        输入流
     * @param clazz     实体类
     * @return 转换后集合
     */
    public List<T> importExcel(String sheetName, InputStream is, Class<T> clazz) throws Exception {
        this.type = Type.IMPORT;
        this.wb = WorkbookFactory.create(is);
        List<T> list = new ArrayList<>();
        Sheet sheet = null;
        if (StringUtils.isNotEmpty(sheetName)) {
            // 如果指定sheet名,则取指定sheet中的内容.
            sheet = wb.getSheet(sheetName);
        } else {
            // 如果传入的sheet名不存在则默认指向第1个sheet.
            sheet = wb.getSheetAt(0);
        }

        if (sheet == null) {
            throw new IOException("Importing sheet can not be found.");
        }

        int rows = sheet.getPhysicalNumberOfRows();

        if (rows > 0) {
            // 定义一个map用于存放excel列的序号和field.
            Map<String, Integer> cellMap = new HashMap<>();
            // 获取表头
            Row head = sheet.getRow(0);
            for (int i = 0; i < head.getPhysicalNumberOfCells(); i++) {
                Cell cell = head.getCell(i);
                if (cell != null) {
                    String value = this.getCellValue(head, i).toString();
                    cellMap.put(value, i);
                } else {
                    cellMap.put(null, i);
                }
            }
            // 有数据时才处理 得到类的所有field.
            Field[] allFields = clazz.getDeclaredFields();
            // 定义一个map用于存放列的序号和field.
            Map<Integer, Field> fieldsMap = new HashMap<>();
            for (int col = 0; col < allFields.length; col++) {
                Field field = allFields[col];
                Excel attr = field.getAnnotation(Excel.class);
                if (attr != null && (attr.type() == Type.ALL || attr.type() == type)) {
                    // 设置类的私有字段属性可访问.
                    field.setAccessible(true);
                    Integer column = cellMap.get(attr.title());
                    fieldsMap.put(column, field);
                }
            }
            for (int i = this.dataRowStart; i < rows; i++) {
                // 从第2行开始取数据,默认第一行是表头.
                Row row = sheet.getRow(i);
                T entity = null;
                for (Map.Entry<Integer, Field> entry : fieldsMap.entrySet()) {
                    Object val = this.getCellValue(row, entry.getKey());

                    // 如果不存在实例则新建.
                    entity = (entity == null ? clazz.newInstance() : entity);
                    // 从map中得到对应列的field.
                    Field field = fieldsMap.get(entry.getKey());
                    // 日期格式
                    String dateFormat = field.getAnnotation(Excel.class).dateFormat();
                    // 取得类型,并根据对象类型设置值.
                    Class<T> fieldType = (Class<T>) field.getType();
                    if (String.class == fieldType) {
                        String s = Converter.toStr(val);
                        if (StringUtils.endsWith(s, ".0")) {
                            val = StringUtils.substringBefore(s, ".0");
                        } else {
                            if (StringUtils.isNotEmpty(dateFormat)) {
                                val = DateHelper.format((Date) val, dateFormat);
                            } else {
                                val = Converter.toStr(val);
                            }
                        }
                    } else if ((Integer.TYPE == fieldType) || (Integer.class == fieldType)) {
                        val = Converter.toInt(val);
                    } else if ((Long.TYPE == fieldType) || (Long.class == fieldType)) {
                        val = Converter.toLong(val);
                    } else if ((Double.TYPE == fieldType) || (Double.class == fieldType)) {
                        val = Converter.toDouble(val);
                    } else if ((Float.TYPE == fieldType) || (Float.class == fieldType)) {
                        val = Converter.toFloat(val);
                    } else if (BigDecimal.class == fieldType) {
                        val = Converter.toBigDecimal(val);
                    } else if (Date.class == fieldType) {
                        if (val instanceof String) {
                            val = DateHelper.from(val.toString(), dateFormat);
                        } else if (val instanceof Double) {
                            val = DateUtil.getJavaDate((Double) val);
                        }
                    }
                    Excel attr = field.getAnnotation(Excel.class);
                    String propertyName = field.getName();
                    if (StringUtils.isNotEmpty(attr.associate())) {
                        propertyName = field.getName() + "." + attr.associate();
                    } else if (StringUtils.isNotEmpty(attr.contentFormat())) {
                        val = reverseByExp(attr, String.valueOf(val), attr.contentFormat());
                    }
                    ReflectUtil.invokeSetter(entity, propertyName, val);
                }
                list.add(entity);
            }
        }
        return list;
    }

    /**
     * 对list数据源将其里面的数据导出到excel表单，excel文件存储到自定义目录。
     *
     * @param savePath  excel文件保存的位置
     * @param fileName  excel文件名称，不带后缀
     * @param sheetName excel导出sheet名称
     * @param clazz     被导出的类
     * @param list      被导出的数据列表
     */
    public void export(String savePath, String fileName, String sheetName, Class<T> clazz, List<T> list) {
        Assert.hasLength(savePath, "File save path must not be empty.");
        this.init(fileName, sheetName, clazz, list, Type.EXPORT);
        processData(list);
        saveFile(savePath, null, null);
    }

    /**
     * 对list数据源将其里面的数据导出到excel表单, Http请求时直接保存，不会存储到服务器。
     *
     * @param fileName  excel文件名称，不带后缀
     * @param sheetName excel导出sheet名称
     * @param clazz     被导出的类
     * @param list      被导出的数据列表
     * @param req       http请求
     * @param resp      http响应
     */
    public void export(String fileName, String sheetName, Class<T> clazz, List<T> list, HttpServletRequest req, HttpServletResponse resp) {
        this.init(fileName, sheetName, clazz, list, Type.EXPORT);
        processData(list);
        saveFile(null, req, resp);
    }

    private void saveFile(String savePath, HttpServletRequest req, HttpServletResponse resp) {
        OutputStream out = null;
        try {
            if (StringHelper.isNotEmpty(savePath)) {
                if (!savePath.endsWith(File.separator)) {
                    savePath += File.separator;
                }
                out = new FileOutputStream(savePath + fileName + ".xlsx");
                wb.write(out);
            } else {
                resp.setContentType("application/x-download");
                resp.setHeader("Content-Disposition", "attachment; filename=" + new String((getFileName() + ".xlsx")
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
                wb.write(resp.getOutputStream());
                resp.getOutputStream().flush();
                resp.getOutputStream().close();
            }
        } catch (Exception e) {
            log.error("Export exception : ", e);
        } finally {
            try {
                wb.close();
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private <T> void processData(List<T> list) {
        double sheetNo = Math.ceil(list.size() / (double) maxRows);
        for (int index = 0; index < sheetNo; index++) {
            createSheet(sheetNo, index);

            // 产生一行
            Row row = sheet.createRow(this.titleRowStart);
            int column = 0;
            // 写入各个字段的列头名称
            for (Object[] os : fields) {
                Excel excel = (Excel) os[1];
                this.createCell(excel, row, column++);
            }
            if (Type.EXPORT.equals(type)) {
                fillExcelData(index, row);
            }
        }
    }

    /**
     * 填充excel数据
     *
     * @param index 序号
     * @param row   单元格行
     */
    private void fillExcelData(int index, Row row) {
        int startNo = index * maxRows;
        int endNo = Math.min(startNo + maxRows, list.size());
        for (int i = startNo; i < endNo; i++) {
            row = sheet.createRow(i + this.dataRowStart - startNo);
            // 得到导出对象.
            T vo = (T) list.get(i);
            int column = 0;
            for (Object[] os : fields) {
                Field field = (Field) os[0];
                Excel excel = (Excel) os[1];
                // 设置实体类私有属性可访问
                field.setAccessible(true);
                this.addCell(excel, row, vo, field, column++);
            }
        }
    }

    /**
     * 创建表格样式
     *
     * @param wb 工作薄对象
     * @return 样式列表
     */
    private Map<String, CellStyle> createStyles(Workbook wb) {
        // 写入各条记录,每条记录对应excel表中的一行
        Map<String, CellStyle> styles = new HashMap<String, CellStyle>();
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.GENERAL);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderRight(BorderStyle.THIN);
        style.setRightBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setBorderLeft(BorderStyle.THIN);
        style.setLeftBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setBorderTop(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        Font dataFont = wb.createFont();
        dataFont.setFontName("宋体");
        dataFont.setFontHeightInPoints((short) 11);
        style.setFont(dataFont);
        styles.put("data", style);

        style = wb.createCellStyle();
        style.cloneStyleFrom(styles.get("data"));
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.SKY_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font headerFont = wb.createFont();
        headerFont.setFontName("黑体");
        headerFont.setFontHeightInPoints((short) 11);
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(headerFont);
        styles.put("header", style);

        return styles;
    }

    /**
     * 创建单元格
     */
    private Cell createCell(Excel attr, Row row, int column) {
        // 创建列
        Cell cell = row.createCell(column);
        // 写入列信息
        cell.setCellValue(attr.title());
        setDataValidation(attr, row, column);
        CellStyle style = styles.get("header");
        style.setAlignment(attr.align());
        style.setVerticalAlignment(attr.verticalAlign());
        cell.setCellStyle(style);
        return cell;
    }

    /**
     * 设置单元格信息
     *
     * @param value 单元格值
     * @param attr  注解相关
     * @param cell  单元格信息
     */
    private void setCellVo(Object value, Excel attr, Cell cell) {
        if (ColumnType.STRING == attr.cellType()) {
            cell.setCellType(CellType.NUMERIC);
            cell.setCellValue(value == null ? attr.defaultValue() : attr.prefix() + value + attr.suffix());
        } else if (ColumnType.NUMERIC == attr.cellType()) {
            cell.setCellType(CellType.NUMERIC);
            cell.setCellValue(Integer.parseInt(value + ""));
        }
    }

    /**
     * 创建表格样式
     */
    private void setDataValidation(Excel attr, Row row, int column) {
        if (attr.title().contains("注：")) {
            sheet.setColumnWidth(column, 6000);
        } else {
            // 设置列宽
            sheet.setColumnWidth(column, (int) ((attr.width() + 0.72) * 256));
            row.setHeight((short) (attr.height() * 20));
        }
        // 如果设置了提示信息则鼠标放上去提示.
        if (StringUtils.isNotEmpty(attr.prompt())) {
            // 这里默认设了2-101列提示.
            setXSSFPrompt(sheet, "", attr.prompt(), 1, 100, column, column);
        }
        // 如果设置了combo属性则本列只能选择不能输入
        if (attr.combo().length > 0) {
            // 这里默认设了2-101列只能选择不能输入.
            setXSSFValidation(sheet, attr.combo(), 1, 100, column, column);
        }
    }

    /**
     * 添加单元格
     */
    private Cell addCell(Excel attr, Row row, T vo, Field field, int column) {
        Cell cell = null;
        try {
            // 设置行高
            row.setHeight((short) (attr.height() * 20));
            // 根据Excel中设置情况决定是否导出,有些情况需要保持为空,希望用户填写这一列.
            if (attr.export()) {
                // 创建cell
                cell = row.createCell(column);
                CellStyle style = styles.get("data");
                style.setAlignment(attr.align());
                style.setVerticalAlignment(attr.verticalAlign());
                cell.setCellStyle(style);

                // 用于读取对象中的属性
                Object value = getTargetValue(vo, field, attr);
                String dateFormat = attr.dateFormat();
                String readConverterExp = attr.contentFormat();
                if (StringUtils.isNotEmpty(dateFormat) && value != null) {
                    cell.setCellValue(DateHelper.format((Date) value, dateFormat));
                } else if (StringUtils.isNotEmpty(readConverterExp) && value != null) {
                    cell.setCellValue(convertByExp(attr, String.valueOf(value), readConverterExp));
                } else {
                    // 设置列类型
                    setCellVo(value, attr, cell);
                }
            }
        } catch (Exception e) {
            log.error("导出Excel失败 : ", e);
        }
        return cell;
    }

    /**
     * 设置 POI XSSFSheet 单元格提示
     *
     * @param sheet         表单
     * @param promptTitle   提示标题
     * @param promptContent 提示内容
     * @param firstRow      开始行
     * @param endRow        结束行
     * @param firstCol      开始列
     * @param endCol        结束列
     */
    private void setXSSFPrompt(Sheet sheet, String promptTitle, String promptContent, int firstRow, int endRow,
                               int firstCol, int endCol) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createCustomConstraint("DD1");
        CellRangeAddressList regions = new CellRangeAddressList(firstRow, endRow, firstCol, endCol);
        DataValidation dataValidation = helper.createValidation(constraint, regions);
        dataValidation.createPromptBox(promptTitle, promptContent);
        dataValidation.setShowPromptBox(true);
        sheet.addValidationData(dataValidation);
    }

    /**
     * 设置某些列的值只能输入预制的数据,显示下拉框.
     *
     * @param sheet    要设置的sheet.
     * @param textlist 下拉框显示的内容
     * @param firstRow 开始行
     * @param endRow   结束行
     * @param firstCol 开始列
     * @param endCol   结束列
     * @return 设置好的sheet.
     */
    private void setXSSFValidation(Sheet sheet, String[] textlist, int firstRow, int endRow, int firstCol, int endCol) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        // 加载下拉列表内容
        DataValidationConstraint constraint = helper.createExplicitListConstraint(textlist);
        // 设置数据有效性加载在哪个单元格上,四个参数分别是：起始行、终止行、起始列、终止列
        CellRangeAddressList regions = new CellRangeAddressList(firstRow, endRow, firstCol, endCol);
        // 数据有效性对象
        DataValidation dataValidation = helper.createValidation(constraint, regions);
        // 处理Excel兼容性问题
        if (dataValidation instanceof XSSFDataValidation) {
            dataValidation.setSuppressDropDownArrow(true);
            dataValidation.setShowErrorBox(true);
        } else {
            dataValidation.setSuppressDropDownArrow(false);
        }

        sheet.addValidationData(dataValidation);
    }

    /**
     * 解析导出值 0=男,1=女,2=未知
     *
     * @param attr          Excel注解
     * @param propertyValue 参数值
     * @param converterExp  翻译注解
     * @return 解析后值
     */
    private static String convertByExp(Excel attr, String propertyValue, String converterExp) {
        String[] convertSource = converterExp.split(attr.contentDelimiter());
        for (String item : convertSource) {
            String[] itemArray = item.split(attr.contentKeyValueDelimiter());
            if (itemArray[0].equals(propertyValue)) {
                return itemArray[1];
            }
        }
        return propertyValue;
    }

    /**
     * 反向解析值 男=0,女=1,未知=2
     *
     * @param attr          Excel注解
     * @param propertyValue 参数值
     * @param converterExp  翻译注解
     * @return 解析后值
     */
    private static String reverseByExp(Excel attr, String propertyValue, String converterExp) {
        try {
            String[] convertSource = converterExp.split(attr.contentDelimiter());
            for (String item : convertSource) {
                String[] itemArray = item.split(attr.contentKeyValueDelimiter());
                if (itemArray[1].equals(propertyValue)) {
                    return itemArray[0];
                }
            }
        } catch (Exception e) {
            throw e;
        }
        return propertyValue;
    }

    /**
     * 编码文件名
     */
    public String encodingFilename(String filename) {
        filename = UUID.randomUUID().toString() + "_" + filename + ".xlsx";
        return filename;
    }

    /**
     * 获取bean中的属性值
     *
     * @param vo    实体对象
     * @param field 字段
     * @param excel 注解
     * @return 最终的属性值
     * @throws Exception
     */
    private Object getTargetValue(T vo, Field field, Excel excel) throws Exception {
        Object o = field.get(vo);
        if (StringUtils.isNotEmpty(excel.associate())) {
            String target = excel.associate();
            if (target.contains(".")) {
                String[] targets = target.split("[.]");
                for (String name : targets) {
                    o = getValue(o, name);
                }
            } else {
                o = getValue(o, target);
            }
        }
        return o;
    }

    /**
     * 以类的属性的get方法方法形式获取值
     *
     * @param o
     * @param name
     * @return value
     * @throws Exception
     */
    private Object getValue(Object o, String name) throws Exception {
        if (StringUtils.isNotEmpty(name)) {
            Class<T> clazz = (Class<T>) o.getClass();
            String methodName = "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
            Method method = clazz.getMethod(methodName);
            o = method.invoke(o);
        }
        return o;
    }

    /**
     * 得到所有定义字段
     */
    private void createExcelField() {
        this.fields = new ArrayList<Object[]>();
        List<Field> tempFields = new ArrayList<>();
        tempFields.addAll(Arrays.asList(clazz.getSuperclass().getDeclaredFields()));
        tempFields.addAll(Arrays.asList(clazz.getDeclaredFields()));
        for (Field field : tempFields) {
            // 单注解
            if (field.isAnnotationPresent(Excel.class)) {
                putToField(field, field.getAnnotation(Excel.class));
            }

            // 多注解
            if (field.isAnnotationPresent(Excels.class)) {
                Excels attrs = field.getAnnotation(Excels.class);
                Excel[] excels = attrs.value();
                for (Excel excel : excels) {
                    putToField(field, excel);
                }
            }
        }
    }

    /**
     * 放到字段集合中
     */
    private void putToField(Field field, Excel attr) {
        if (attr != null && (attr.type() == Type.ALL || attr.type() == type)) {
            this.fields.add(new Object[]{field, attr});
        }
    }

    /**
     * 创建一个工作簿
     */
    private void createWorkbook() {
        this.wb = new SXSSFWorkbook(500);
    }

    /**
     * 创建工作表
     *
     * @param sheetNo sheet数量
     * @param index   序号
     */
    private void createSheet(double sheetNo, int index) {
        this.sheet = wb.createSheet();
        this.styles = createStyles(wb);
        // 设置工作表的名称.
        if (sheetNo == 0) {
            wb.setSheetName(index, sheetName);
        } else {
            wb.setSheetName(index, sheetName + "_" + index);
        }
    }

    /**
     * 获取单元格值
     *
     * @param row    获取的行
     * @param column 获取单元格列号
     * @return 单元格值
     */
    private Object getCellValue(Row row, int column) {
        if (row == null) {
            return row;
        }
        Object val = "";
        try {
            Cell cell = row.getCell(column);
            if (cell != null) {
                if (cell.getCellTypeEnum() == CellType.NUMERIC || cell.getCellTypeEnum() == CellType.FORMULA) {
                    val = cell.getNumericCellValue();
                    if (HSSFDateUtil.isCellDateFormatted(cell)) {
                        val = DateUtil.getJavaDate((Double) val); // POI Excel 日期格式转换
                    } else {
                        if ((Double) val % 1 > 0) {
                            val = new DecimalFormat("0.00").format(val);
                        } else {
                            val = new DecimalFormat("0").format(val);
                        }
                    }
                } else if (cell.getCellTypeEnum() == CellType.STRING) {
                    val = cell.getStringCellValue();
                } else if (cell.getCellTypeEnum() == CellType.BOOLEAN) {
                    val = cell.getBooleanCellValue();
                } else if (cell.getCellTypeEnum() == CellType.ERROR) {
                    val = cell.getErrorCellValue();
                }

            }
        } catch (Exception e) {
            return val;
        }
        return val;
    }

    public ExcelConfig getExcelConfig() {
        return excelConfig;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public int getTitleRowStart() {
        return titleRowStart;
    }

    public int getDataRowStart() {
        return dataRowStart;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSheetName() {
        return sheetName;
    }

    public Type getType() {
        return type;
    }

    public List<T> getList() {
        return list;
    }

    public List<Object[]> getFields() {
        return fields;
    }

    public Class<T> getClazz() {
        return clazz;
    }
}