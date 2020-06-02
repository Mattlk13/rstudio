/*
 * CommandPalette.java
 *
 * Copyright (C) 2020 by RStudio, PBC
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.palette.ui;

import java.util.ArrayList;
import java.util.List;

import org.rstudio.core.client.ElementIds;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.a11y.A11y;
import org.rstudio.core.client.widget.AriaLiveStatusWidget;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.AriaLiveStatusEvent.Severity;
import org.rstudio.studio.client.palette.model.CommandPaletteEntrySource;
import org.rstudio.studio.client.palette.model.CommandPaletteItem;

import com.google.gwt.aria.client.ExpandedValue;
import com.google.gwt.aria.client.Id;
import com.google.gwt.aria.client.Roles;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

/**
 * CommandPalette is a widget that displays all available RStudio commands in a
 * searchable list.
 */
public class CommandPalette extends Composite
{
   private static CommandPaletteUiBinder uiBinder = GWT.create(CommandPaletteUiBinder.class);

   interface CommandPaletteUiBinder extends UiBinder<Widget, CommandPalette>
   {
   }
   
   /**
    * The host interface represents the class hosting the widget (not a widget
    * itself), which is currently the CommandPaletteLauncher.
    */
   public interface Host
   {
      void dismiss();
   }
   
   public interface Styles extends CssResource
   {
      String popup();
      String searchBox();
      String commandList();
      String commandPanel();
   }

   public CommandPalette(List<CommandPaletteEntrySource> sources, Host host)
   {
      initWidget(uiBinder.createAndBindUi(this));

      items_ = new ArrayList<>();
      host_ = host;
      selected_ = -1;
      attached_ = false;
      pageSize_ = 0;
      sources_ = sources;
      styles_.ensureInjected();
      
      Element searchBox = searchBox_.getElement();
      searchBox.setAttribute("placeholder", "Search and run commands");
      searchBox.setAttribute("spellcheck", "false");
      searchBox.setAttribute("autocomplete", "off");

      // Accessibility attributes: list box
      Element commandList = commandList_.getElement();
      ElementIds.assignElementId(commandList, ElementIds.COMMAND_PALETTE_LIST);
      Roles.getListboxRole().set(commandList);
      Roles.getListboxRole().setAriaLabelProperty(commandList, "Matching commands and settings");

      // Accessibility attributes: search box
      ElementIds.assignElementId(searchBox_, ElementIds.COMMAND_PALETTE_SEARCH);
      Roles.getComboboxRole().setAriaOwnsProperty(searchBox, Id.of(commandList_.getElement()));
      Roles.getComboboxRole().set(searchBox);
      Roles.getComboboxRole().setAriaLabelProperty(searchBox, "Search for commands and settings");
      Roles.getComboboxRole().setAriaExpandedState(searchBox, ExpandedValue.TRUE);
      A11y.setARIAAutocomplete(searchBox_, "list");
      
      // Populate the palette on a deferred callback so that it appears immediately
      Scheduler.get().scheduleDeferred(() ->
      {
         populate();
      });
      
   }
   
   @Override
   public void onAttach()
   {
      super.onAttach();

      attached_ = true;

      // If we have already populated, compute the page size. Do this deferred
      // so that a render pass occurs (otherwise the page size computations will
      // take place with unrendered elements)
      if (items_.size() > 0)
      {
         Scheduler.get().scheduleDeferred(() ->
         {
            computePageSize();
         });
      }
   }

   private void renderAll(CommandPaletteEntrySource source)
   {
      List<CommandPaletteItem> items = source.getCommandPaletteItems();
      if (items == null)
         return;
      
      for (CommandPaletteItem item: items)
      {
         Widget w = item.asWidget();
         if (w != null)
         {
            items_.add(item);
            commandList_.add(w);
         }
      }
   }

   /**
    * Performs a one-time population of the palette with all available commands.
    */
   private void populate()
   {
      for (CommandPaletteEntrySource source: sources_)
      {
         renderAll(source);
      }
      
      // Invoke commands when they're clicked on
      for (CommandPaletteItem item: items_)
      {
         Widget w = item.asWidget();
         w.sinkEvents(Event.ONCLICK);
         w.addHandler((evt) -> {
            item.invoke();
         }, ClickEvent.getType());
      }
      
      // Handle most keystrokes on KeyUp so that the contents of the text box
      // have already been changed
      searchBox_.addKeyUpHandler((evt) ->
      {
         if (evt.getNativeKeyCode() == KeyCodes.KEY_ESCAPE)
         {
            // Pressing ESC dismisses the host (removing the palette popup)
            host_.dismiss();
         }
         else if (evt.getNativeKeyCode() == KeyCodes.KEY_ENTER)
         {
            // Enter runs the selected command
            invokeSelection();
         }
         else
         {
            // Just update the filter if the text has changed
            String searchText = searchBox_.getText();
            if (!StringUtil.equals(searchText_, searchText))
            {
               searchText_ = searchText;
               applyFilter();
            }
         }
      });

      // Up and Down arrows need to be handled on KeyDown to account for
      // repetition (a held arrow key will generate multiple KeyDown events and
      // then a single KeyUp when released)
      searchBox_.addKeyDownHandler((evt) -> 
      {
         // Ignore the Tab key so we don't lose focus accidentally (there is
         // only one focusable element in the palette and we don't want Tab to
         // dismiss it)
         if (evt.getNativeKeyCode() == KeyCodes.KEY_TAB)
         {
            evt.stopPropagation();
            evt.preventDefault();
            return;
         }

         // Ignore modified arrows so that e.g. Shift Up/Down to select the
         // contents of the textbox work as expected
         if (evt.isAnyModifierKeyDown())
            return;
         
         if (evt.getNativeKeyCode() == KeyCodes.KEY_UP)
         {
            // Directional keys often trigger behavior in textboxes (e.g. moving
            // the cursor to the beginning/end of text) but we're hijacking them
            // to do navigation in the results list, so disable that.
            evt.stopPropagation();
            evt.preventDefault();
            moveSelection(-1);
         }
         else if (evt.getNativeKeyCode() == KeyCodes.KEY_DOWN)
         {
            evt.stopPropagation();
            evt.preventDefault();
            moveSelection(1);
         }
         else if (evt.getNativeKeyCode() == KeyCodes.KEY_PAGEUP)
         {
            // Page Up moves up by the page size (computed based on the size of
            // entries in the DOM)
            moveSelection(-1 * pageSize_);
         }
         else if (evt.getNativeKeyCode() == KeyCodes.KEY_PAGEDOWN)
         {
            moveSelection(pageSize_);
         }
      });
      
      // If we are already attached to the DOM at this point, compute the page
      // size for scrolling by pages. 
      if (attached_)
      {
         computePageSize();
      }
   }
   
   /**
    * Compute the size of a "page" of results (for Page Up / Page Down). We do
    * this dynamically based on measuring DOM elements since the number of items
    * that fit in a page can vary based on platform, browser, and available
    * fonts.
    */
   private void computePageSize()
   {
      // Find the first visible entry (we can't measure an invisible one)
      for (CommandPaletteItem item: items_)
      {
         Widget entry = item.asWidget();
         if (entry.isVisible())
         {
            // Compute the page size: the total size of the scrolling area
            // divided by the size of a visible entry
            pageSize_ = Math.floorDiv(scroller_.getOffsetHeight(), 
                  entry.getOffsetHeight());
            break;
         }
      }
      
      if (pageSize_ > 1)
      {
         // We want the virtual page to be very slightly smaller than the
         // physical page
         pageSize_--;
      }
      else
      {
         // Something went wrong and we got a tiny or meaningless page size. Use
         // 10 items as a default.
         pageSize_ = 10;
      }
   }
   
   /**
    * Filter the commands by the current contents of the search box
    */
   private void applyFilter()
   {
      int matches = 0;

      // Split the search text into a series of lowercase words. This provides a
      // kind of partial fuzzy matching, so that e.g., "new py" matches the command
      // "Create a new Python script".
      String[] needles = searchBox_.getText().toLowerCase().split("\\s+");
      
      for (CommandPaletteItem item: items_)
      {
         if (item.matchesSearch(needles))
         {
            item.setSearchHighlight(needles);
            item.asWidget().setVisible(true);
            matches++;
         }
         else
         {
            item.asWidget().setVisible(false);
         }
      }
      
      // If not searching for anything, then searching for everything.
      if (needles.length == 0)
      {
         matches = items_.size();
      }
      
      updateSelection();
      
      // Show "no results" message if appropriate
      if (matches == 0 && !noResults_.isVisible())
      {
         scroller_.setVisible(false);
         noResults_.setVisible(true);
      }
      else if (matches > 0 && noResults_.isVisible())
      {
         scroller_.setVisible(true);
         noResults_.setVisible(false);
      }

      // Report results count to screen reader
      resultsCount_.reportStatus(matches + " " +
            "commands found, press up and down to navigate",
            RStudioGinjector.INSTANCE.getUserPrefs().typingStatusDelayMs().getValue(),
            Severity.STATUS);
   }
   
   /**
    * Selects the topmost search result in the palette
    */
   public void updateSelection()
   {
      for (int i = 0; i < items_.size(); i++)
      {
         if (!items_.get(i).asWidget().isVisible())
         {
            continue;
         }
         
         selectNewCommand(i);
         break;
      }
   }
   
   /**
    * Changes the selected palette entry in response to user input.
    * 
    * @param units The number of units to move selection (negative to go
    *   backwards)
    */
   private void moveSelection(int units)
   {
      // Select the first visible command in the given direction
      CommandPaletteItem candidate = null;

      int direction = units / Math.abs(units);  // -1 (backwards) or 1 (forwards)
      int consumed = 0; // Number of units consumed to goal
      int target = 0;   // Target element to select
      int pass = 1;     // Number of entries passed so far
      int viable = -1;  // The last visited viable (selectable) entry
      do
      {
         target = selected_ + (direction * pass++);
         if (target < 0 || target >= items_.size())
         {
            // Request to navigate outside the boundaries of the palette
            break;
         }
         candidate = items_.get(target);

         if (candidate.asWidget().isVisible())
         {
            // This entry is visible, so it counts against our goal.
            consumed += direction;
            viable = target;
         }
      }
      while (consumed != units);
      
      // Select a viable entry if we found one; this may not be the desired
      // element but will be as far as we could move (e.g. requested to move
      // 20 units but had to stop at 15).
      if (viable >= 0)
      {
         selectNewCommand(viable);
      }
   }
   
   /**
    * Focuses the palette's search box in preparation for user input.
    */
   public void focus()
   {
      searchBox_.setFocus(true);
      updateSelection();
   }
   
   /**
    * Invoke the currently selected command.
    */
   private void invokeSelection()
   {
      if (selected_ >= 0)
      {
         if (items_.get(selected_).dismissOnInvoke())
         {
            host_.dismiss();
         }
         items_.get(selected_).invoke();
      }
   }
   
   /**
    * Change the selected command.
    * 
    * @param target The index of the command to select.
    */
   private void selectNewCommand(int target)
   {
      // No-op if target was already selected
      if (selected_ == target)
         return;
      
      // Clear previous selection, if any
      if (selected_ >= 0)
      {
         items_.get(selected_).setSelected(false);
      }
      
      // Set new selection
      selected_ = target;
      CommandPaletteItem selected = items_.get(selected_);
      selected.setSelected(true);
      selected.asWidget().getElement().scrollIntoView();

      // Update active descendant for accessibility
      Roles.getComboboxRole().setAriaActivedescendantProperty(
            searchBox_.getElement(), Id.of(selected.asWidget().getElement()));
   }
   
   private final Host host_;
   private final List<CommandPaletteEntrySource> sources_;
   private int selected_;
   private List<CommandPaletteItem> items_;
   private String searchText_;
   private boolean attached_;
   private int pageSize_;

   @UiField public TextBox searchBox_;
   @UiField public FlowPanel commandList_;
   @UiField AriaLiveStatusWidget resultsCount_;
   @UiField HTMLPanel noResults_;
   @UiField ScrollPanel scroller_;
   @UiField Styles styles_;
}
