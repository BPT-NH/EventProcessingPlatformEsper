/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.agg.aggregator;

import java.lang.reflect.Array;

/**
 * Count all non-null values.
 */
public class AggregatorCountNonNullFilter implements AggregationMethod
{
    protected long numDataPoints;

    public void clear()
    {
        numDataPoints = 0;
    }

    public void enter(Object object)
    {
        if (checkPass(object)) {
            numDataPoints++;
        }
    }

    public void leave(Object object)
    {
        if (checkPass(object)) {
            numDataPoints--;
        }
    }

    public Object getValue()
    {
        return numDataPoints;
    }

    public Class getValueType()
    {
        return Long.class;
    }

    private boolean checkPass(Object object) {
        Boolean first = (Boolean) Array.get(object, 1);
        if (first != null) {
            return first;
        }
        return false;
    }
}
