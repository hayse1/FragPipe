/* 
 * Copyright (C) 2018 Dmitry Avtonomov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.dmtavt.fragpipe.api;

import com.github.chhh.utils.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import javax.swing.table.DefaultTableModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dmtavt.fragpipe.params.fragger.Mod;

/**
 *
 * @author Dmitry Avtonomov
 */
public class ModsTableModel extends DefaultTableModel {
    private static final Logger log = LoggerFactory.getLogger(ModsTableModel.class);
    
    private static final long serialVersionUID = 1L;
    private Class<?>[] classes;
    private String[] colNames;
    private boolean[]canEdit;
    /** [0] - which col is Enabled, [1] - which col is Site Name, [2] - which col is mass delta. */
    private int[] modMapping;
    private static final int COL_ENABLED = 0;
    private static final int COL_SITES = 1;
    private static final int COL_DELTA = 2;
    private static final int COL_MAX_OCCURS = 3;

    

    /**
     * 
     * @param colNames  Names of columns.
     * @param classes  Types of columns.
     * @param canEdit  Which columns are editable.
     * @param data  Can be null.
     */
    public ModsTableModel(String[] colNames, Class<?> [] classes, boolean [] canEdit,
            int[] modMapping, Object[][] data) {
        super(data, colNames);
        this.colNames = colNames;
        this.canEdit = canEdit;
        this.classes = classes;
        this.modMapping = modMapping;
    }

    public Class<?> getColumnClass(int columnIndex) {
        return classes [columnIndex];
    }

    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return canEdit [columnIndex];
    }
    
    public List<Mod> getModifications() {
        ArrayList<Mod> list = new ArrayList<>(dataVector.size());
        for (int i = 0; i < dataVector.size(); i++) {
            Vector<?> row = (Vector<?>)dataVector.get(i);
            if (row != null) {
                Double delta = (Double)row.get(COL_DELTA);
                String sites = (String)row.get(COL_SITES);
                Boolean enabled = (Boolean)row.get(COL_ENABLED);
                int maxOccurrences = -1;
                if (row.size() > COL_MAX_OCCURS) {
                    Object maxOccursCol = row.get(COL_MAX_OCCURS);
                    if (maxOccursCol != null) {
                        maxOccurrences = (Integer)row.get(COL_MAX_OCCURS);
                    } else {
                        maxOccurrences = 1;
                    }
                }
                if (!StringUtils.isNullOrWhitespace(sites) && delta != null) {
                    Mod m = new Mod(delta, sites, enabled, maxOccurrences);
                    list.add(m);
                }
            } else {
                // row is null? strange, but ok
                log.warn("Null row found in modifications table");
            }
            
        }
        return list;
    }
}