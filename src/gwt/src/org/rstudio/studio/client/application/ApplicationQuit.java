/*
 * ApplicationQuit.java
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
package org.rstudio.studio.client.application;

import java.util.ArrayList;

import org.rstudio.core.client.Barrier;
import org.rstudio.core.client.Barrier.Token;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.events.BarrierReleasedEvent;
import org.rstudio.core.client.events.BarrierReleasedHandler;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.widget.Operation;
import org.rstudio.core.client.widget.OperationWithInput;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.application.events.HandleUnsavedChangesEvent;
import org.rstudio.studio.client.application.events.HandleUnsavedChangesHandler;
import org.rstudio.studio.client.application.events.RestartEvent;
import org.rstudio.studio.client.application.events.RestartHandler;
import org.rstudio.studio.client.application.events.SaveActionChangedEvent;
import org.rstudio.studio.client.application.events.SaveActionChangedHandler;
import org.rstudio.studio.client.application.model.ApplicationServerOperations;
import org.rstudio.studio.client.application.model.SaveAction;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.GlobalProgressDelayer;
import org.rstudio.studio.client.common.filetypes.FileIconResources;
import org.rstudio.studio.client.projects.Projects;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.WorkbenchContext;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.events.LastChanceSaveEvent;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.UnsavedChangesTarget;
import org.rstudio.studio.client.workbench.ui.unsaved.UnsavedChangesDialog;
import org.rstudio.studio.client.workbench.ui.unsaved.UnsavedChangesDialog.Result;
import org.rstudio.studio.client.workbench.views.source.SourceShim;

import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.client.Command;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ApplicationQuit implements SaveActionChangedHandler,
                                        HandleUnsavedChangesHandler,
                                        RestartHandler
{
   public interface Binder extends CommandBinder<Commands, ApplicationQuit> {}
   
   @Inject
   public ApplicationQuit(ApplicationServerOperations server,
                          GlobalDisplay globalDisplay,
                          EventBus eventBus,
                          Session session,
                          WorkbenchContext workbenchContext,
                          SourceShim sourceShim,
                          Commands commands,
                          Binder binder)
   {
      // save references
      server_ = server;
      globalDisplay_ = globalDisplay;
      eventBus_ = eventBus;
      session_ = session;
      workbenchContext_ = workbenchContext;
      sourceShim_ = sourceShim;
      
      // bind to commands
      binder.bind(commands, this);
      
      // subscribe to events
      eventBus.addHandler(SaveActionChangedEvent.TYPE, this);   
      eventBus.addHandler(HandleUnsavedChangesEvent.TYPE, this);
      eventBus.addHandler(RestartEvent.TYPE, this);
   }
   
  
   // notification that we are ready to quit
   public interface QuitContext
   {
      void onReadyToQuit(boolean saveChanges);
   }
   
   public void prepareForQuit(String caption,
                              final QuitContext quitContext)
   {   
      // see what the unsaved changes situation is and prompt accordingly
      final int saveAction = saveAction_.getAction();
      ArrayList<UnsavedChangesTarget> unsavedSourceDocs = 
                                             sourceShim_.getUnsavedChanges();
      
      // no unsaved changes at all
      if (saveAction != SaveAction.SAVEASK && unsavedSourceDocs.size() == 0)
      {
         quitContext.onReadyToQuit(saveAction == SaveAction.SAVE);
      }
      
      // just an unsaved environment
      else if (unsavedSourceDocs.size() == 0) 
      {        
         // confirm quit and do it
         String prompt = "Save workspace image to " + 
                         workbenchContext_.getREnvironmentPath() + "?";
         globalDisplay_.showYesNoMessage(
               GlobalDisplay.MSG_QUESTION,
               caption,
               prompt,
               true,
               new Operation() { public void execute()
               {
                  quitContext.onReadyToQuit(true);      
               }},
               new Operation() { public void execute()
               {
                  quitContext.onReadyToQuit(false);
               }},
               new Operation() { public void execute()
               {
               }},
               "Save",
               "Don't Save",
               true);        
      }
      
      // a single unsaved document
      else if (saveAction != SaveAction.SAVEASK && 
               unsavedSourceDocs.size() == 1)
      {
         sourceShim_.saveWithPrompt(
           unsavedSourceDocs.get(0), 
           sourceShim_.revertUnsavedChangesBeforeExitCommand(new Command() {
               @Override
               public void execute()
               {
                  quitContext.onReadyToQuit(saveAction == SaveAction.SAVE);
               }}),
           null);
      }
      
      // multiple save targets
      else
      {
         ArrayList<UnsavedChangesTarget> unsaved = 
                                      new ArrayList<UnsavedChangesTarget>();
         if (saveAction == SaveAction.SAVEASK)
            unsaved.add(globalEnvTarget_);
         unsaved.addAll(unsavedSourceDocs);
         new UnsavedChangesDialog(
            caption,
            unsaved,
            new OperationWithInput<UnsavedChangesDialog.Result>() {

               @Override
               public void execute(Result result)
               {
                  ArrayList<UnsavedChangesTarget> saveTargets =
                                                result.getSaveTargets();       
                  
                  // remote global env target from list (if specified) and 
                  // compute the saveChanges value
                  boolean saveGlobalEnv = saveAction == SaveAction.SAVE;
                  if (saveAction == SaveAction.SAVEASK)
                     saveGlobalEnv = saveTargets.remove(globalEnvTarget_);
                  final boolean saveChanges = saveGlobalEnv;
                  
                  // save specified documents and then quit
                  sourceShim_.handleUnsavedChangesBeforeExit(
                        saveTargets,                                     
                        new Command() {
                           @Override
                           public void execute()
                           {
                              quitContext.onReadyToQuit(saveChanges);
                           }
                        });
               }
               
            },
            
            // no cancel operation
            null
            
            ).showModal();
      }
      
   }
   
   public void performQuit(boolean saveChanges, 
                           String switchToProject)
   {
      performQuit(null, saveChanges, switchToProject);
   }
   
   public void performQuit(String progressMessage,
                           boolean saveChanges, 
                           String switchToProject)
   {
      new QuitCommand(progressMessage, saveChanges, switchToProject).execute();
   }
   
   @Override
   public void onSaveActionChanged(SaveActionChangedEvent event)
   {
      saveAction_ = event.getAction();
   }
   
   @Override
   public void onHandleUnsavedChanges(HandleUnsavedChangesEvent event)
   {
      // command which will be used to callback the server
      class HandleUnsavedCommand implements Command
      {
         public HandleUnsavedCommand(boolean handled)
         {
            handled_ = handled;
         }
         
         @Override
         public void execute()
         {
            server_.handleUnsavedChangesCompleted(
                                          handled_, 
                                          new VoidServerRequestCallback());  
         }
         
         private final boolean handled_;
      };
      
      // get unsaved source docs
      ArrayList<UnsavedChangesTarget> unsavedSourceDocs = 
                                          sourceShim_.getUnsavedChanges();
      
      if (unsavedSourceDocs.size() == 1)
      {
         sourceShim_.saveWithPrompt(
               unsavedSourceDocs.get(0), 
               sourceShim_.revertUnsavedChangesBeforeExitCommand(
                                             new HandleUnsavedCommand(true)),
               new HandleUnsavedCommand(false));
      }
      else if (unsavedSourceDocs.size() > 1)
      {
         new UnsavedChangesDialog(
               "Quit R Session",
               unsavedSourceDocs,
               new OperationWithInput<UnsavedChangesDialog.Result>() {
                  @Override
                  public void execute(Result result)
                  {
                     // save specified documents and then quit
                     sourceShim_.handleUnsavedChangesBeforeExit(
                           result.getSaveTargets(),
                           new HandleUnsavedCommand(true));
                  }
                },
                new HandleUnsavedCommand(false)
         ).showModal();
      }
      else
      {
         new HandleUnsavedCommand(true).execute();
      }
   }
      
     
   @Handler
   public void onRestartR()
   {
      eventBus_.fireEvent(new RestartEvent());
   }
   
   @Override
   public void onRestart(RestartEvent event)
   {
      prepareForQuit("Restart R Session", new QuitContext() {

         @Override
         public void onReadyToQuit(boolean saveChanges)
         {
            String projFile = session_.getSessionInfo().getActiveProjectFile();
            performQuit("Restarting R...", 
                        saveChanges, 
                        projFile != null ? projFile : Projects.NONE);
            
         }
         
      });
   }
   
   @Handler
   public void onQuitSession()
   {
      prepareForQuit("Quit R Session", new QuitContext() {
         public void onReadyToQuit(boolean saveChanges)
         {
            performQuit(saveChanges, null);
         }   
      });
   }
   
   private UnsavedChangesTarget globalEnvTarget_ = new UnsavedChangesTarget()
   {
      @Override
      public String getId()
      {
         return "F59C8727-3C63-41F4-989C-B1E1D47760E3";
      }

      @Override
      public ImageResource getIcon()
      {
         return FileIconResources.INSTANCE.iconRdata(); 
      }

      @Override
      public String getTitle()
      {
         return "Workspace image (.RData)";
      }

      @Override
      public String getPath()
      {
         return workbenchContext_.getREnvironmentPath();
      }
      
   };
   
   private String buildSwitchMessage(String switchToProject)
   {
      String msg = !switchToProject.equals("none") ?
        "Switching to project " + 
           FileSystemItem.createFile(switchToProject).getParentPathString() :
        "Closing project";
      return msg + "...";
   }
   
   private class QuitCommand implements Command 
   { 
      public QuitCommand(String progressMessage, 
                         boolean saveChanges, 
                         String switchToProject)
      {
         progressMessage_ = progressMessage;
         saveChanges_ = saveChanges;
         switchToProject_ = switchToProject;
      }
      
      public void execute()
      {
         // show delayed progress
         String msg = progressMessage_;
         if (msg == null)
         {
            msg = switchToProject_ != null ? 
                                    buildSwitchMessage(switchToProject_) :
                                    "Quitting R Session...";
         }
         final GlobalProgressDelayer progress = new GlobalProgressDelayer(
                                                               globalDisplay_,
                                                               250,
                                                               msg);

         // Use a barrier and LastChanceSaveEvent to allow source documents
         // and client state to be synchronized before quitting.
         Barrier barrier = new Barrier();
         barrier.addBarrierReleasedHandler(new BarrierReleasedHandler()
         {
            public void onBarrierReleased(BarrierReleasedEvent event)
            {
               // All last chance save operations have completed (or possibly
               // failed). Now do the real quit.

               // if a switch to project path is defined then set it
               if (Desktop.isDesktop() && (switchToProject_ != null))
                  Desktop.getFrame().setSwitchToProjectPending(true);

               server_.quitSession(
                  saveChanges_,
                  switchToProject_,
                  new ServerRequestCallback<Void>()
                  {
                     @Override
                     public void onResponseReceived(Void response)
                     {
                        // clear progress only if we aren't switching projects
                        // (otherwise we want to leave progress up until
                        // the app reloads)
                        if (switchToProject_ == null)
                           progress.dismiss();
                     }

                     @Override
                     public void onError(ServerError error)
                     {
                        progress.dismiss();

                        if (Desktop.isDesktop())
                           Desktop.getFrame().setSwitchToProjectPending(false);
                     }
                  });
            }
         });

         // We acquire a token to make sure that the barrier doesn't fire before
         // all the LastChanceSaveEvent listeners get a chance to acquire their
         // own tokens.
         Token token = barrier.acquire();
         try
         {
            eventBus_.fireEvent(new LastChanceSaveEvent(barrier));
         }
         finally
         {
            token.release();
         }
      }

      private final boolean saveChanges_;
      private final String switchToProject_;
      private final String progressMessage_;

   };

   private final ApplicationServerOperations server_;
   private final GlobalDisplay globalDisplay_;
   private final EventBus eventBus_;
   private final WorkbenchContext workbenchContext_;
   private final Session session_;
   private final SourceShim sourceShim_;
   
   private SaveAction saveAction_ = SaveAction.saveAsk();
  
}
