/*
 * RestartEvent.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.application.events;

import com.google.gwt.event.shared.GwtEvent;

public class RestartEvent extends GwtEvent<RestartHandler>
{
   public static final GwtEvent.Type<RestartHandler> TYPE =
      new GwtEvent.Type<RestartHandler>();
   
   public RestartEvent()
   {
   }
 
   
   @Override
   protected void dispatch(RestartHandler handler)
   {
      handler.onRestart(this);
   }

   @Override
   public GwtEvent.Type<RestartHandler> getAssociatedType()
   {
      return TYPE;
   }
}