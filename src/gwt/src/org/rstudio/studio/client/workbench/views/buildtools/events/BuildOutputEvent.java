/*
 * BuildOutputEvent.java
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
package org.rstudio.studio.client.workbench.views.buildtools.events;

import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;

public class BuildOutputEvent extends GwtEvent<BuildOutputEvent.Handler>
{
   public interface Handler extends EventHandler
   {
      void onBuildOutput(BuildOutputEvent event);
   }

   public BuildOutputEvent(String output)
   {
      output_ = output;
   }
   
   public String getOutput()
   {
      return output_;
   }

   @Override
   public Type<Handler> getAssociatedType()
   {
      return TYPE;
   }

   @Override
   protected void dispatch(Handler handler)
   {
      handler.onBuildOutput(this);
   }
   
   private String output_;

   public static final Type<Handler> TYPE = new Type<Handler>();
}
