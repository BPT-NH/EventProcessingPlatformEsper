/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.spec;

import com.espertech.esper.util.MetaDefItem;

import java.util.List;
import java.util.LinkedList;
import java.io.Serializable;

/**
 * Descriptor generated by INSERT-INTO clauses specified in expressions to insert the
 * results of statement as a stream to further statements.
 */
public class InsertIntoDesc implements MetaDefItem, Serializable
{
    private final SelectClauseStreamSelectorEnum streamSelector;
    private final String eventTypeName;
    private List<String> columnNames;
    private static final long serialVersionUID = 6204369134039715720L;

    /**
     * Ctor.
     * @param streamSelector selects insert, remove or insert+remove stream
     * @param eventTypeName is the event type name
     */
    public InsertIntoDesc(SelectClauseStreamSelectorEnum streamSelector, String eventTypeName)
    {
        this.streamSelector = streamSelector;
        this.eventTypeName = eventTypeName;
        columnNames = new LinkedList<String>();
    }

    /**
     * Returns the stream(s) selected for inserting into.
     * @return stream selector
     */
    public SelectClauseStreamSelectorEnum getStreamSelector() {
        return streamSelector;
    }

    /**
     * Returns name of event type to use for insert-into stream.
     * @return event type name
     */
    public String getEventTypeName()
    {
        return eventTypeName;
    }

    /**
     * Returns a list of column names specified optionally in the insert-into clause, or empty if none specified.
     * @return column names or empty list if none supplied
     */
    public List<String> getColumnNames()
    {
        return columnNames;
    }

    /**
     * Add a column name to the insert-into clause.
     * @param columnName to add
     */
    public void add(String columnName)
    {
        columnNames.add(columnName);
    }
}
