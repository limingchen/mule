/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.heisenberg.extension;

import org.mule.runtime.core.api.NestedProcessor;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.runtime.operation.Result;

import java.lang.annotation.Repeatable;
import java.util.List;

/**
 * //TODO
 */
public class HeisenbergTry {

  public Result<Object, Object> tryThing(NestedProcessor<HeisenbergSource> source,
                                         Route<HeisenbergOperations> route,
                                         @Optional @Size(min=0, max=1) List<Route<HeisenbergOperations>> routes,
                                         List<NestedProcessor<HeisenbergOperations>> singleRoute,
                                         @Repeatable(minOccurs="1", maxOccurs="unlimited")List<NestedProcessor> repeatableRoute){



  }

}
