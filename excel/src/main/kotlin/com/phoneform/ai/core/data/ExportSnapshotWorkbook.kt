package com.phoneform.ai.core.data

import com.phoneform.ai.core.model.ExportCellType
import com.phoneform.ai.core.model.ExportSnapshot

/** Uses only the frozen snapshot; no live project or repository lookups are permitted. */
fun ExportSnapshot.toXlsxSheets(): List<XlsxSheet> = sheets.map { sheet ->
    XlsxSheet(sheet.name, sheet.headers.toList()) {
        sheet.rows.asSequence().map { row ->
            row.map { cell -> when {
                cell.value.isEmpty() -> XlsxCell.Empty
                cell.type == ExportCellType.NUMBER -> XlsxCell.Number(cell.value)
                else -> XlsxCell.Text(cell.value)
            } }
        }
    }
}
