/**
 * File: AzExecPlugin.java
 * Library: azexec
 * Date: 2 Jun 2008
 *
 * Author: Allan Crooks
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the COPYING file ).
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.aelitis.azureus.plugins.azexec;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.biglybt.core.tag.Tag;
import com.biglybt.core.tag.TagManagerFactory;
import com.biglybt.core.tag.TagType;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.config.ConfigParameter;
import com.biglybt.pif.config.ConfigParameterListener;
import com.biglybt.pif.config.PluginConfigSource;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadCompletionListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pif.ui.UIInputReceiver;
import com.biglybt.pif.ui.UIInputReceiverListener;
import com.biglybt.pif.ui.UIInstance;
import com.biglybt.pif.ui.config.*;
import com.biglybt.pif.ui.menus.MenuItem;
import com.biglybt.pif.ui.menus.MenuItemFillListener;
import com.biglybt.pif.ui.menus.MenuItemListener;
import com.biglybt.pif.ui.menus.MenuManager;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.biglybt.ui.swt.Utils;
import com.biglybt.ui.swt.pif.UISWTInputReceiver;
import com.sun.corba.se.impl.javax.rmi.CORBA.Util;

public class AzExecPlugin implements Plugin, DownloadCompletionListener, MenuItemListener, MenuItemFillListener {
	
	private BasicPluginViewModel model;
	private LoggerChannel channel;
	private PluginInterface plugin_interface;
	private PluginConfig cfg;
	private TorrentAttribute attr;
	private TorrentAttribute ta_cat;
	private static int HISTORY_LIMIT = 15;
	private BooleanParameter use_runtime_exec_param;
	
	private String exec_cmd;
	
	private final String history_attrib = "run_history";
	
	@Override
	public void initialize(final PluginInterface plugin_interface) {
		this.plugin_interface = plugin_interface;
		this.cfg = plugin_interface.getPluginconfig();
		PluginConfigSource pcs = cfg.enableExternalConfigSource();
		pcs.initialize();
		
		final String BLANK_CMD = plugin_interface.getUtilities().getLocaleUtilities().localise("azexec.auto_set.command.value.blank");
		
		BasicPluginConfigModel model = plugin_interface.getUIManager().createBasicPluginConfigModel("azexec");
		final BooleanParameter auto_set_enabled = model.addBooleanParameter2("auto_set_enabled", "azexec.auto_set.enabled", false);
		
		// Using a string parameter because I prefer the look of it rather than label
		// parameter (besides - it gives me more vertical space). Also - changing
		// the text of a label parameter won't relayout the view.
		final StringParameter auto_set_label = model.addStringParameter2("_", "azexec.auto_set.command.label", BLANK_CMD);
		
		// We don't want to allow input - we want to use the combo box.
		auto_set_label.setEnabled(false);
		
		ActionParameter auto_set_action = model.addActionParameter2("azexec.auto_set.action.label", "azexec.auto_set.action.exec");
		ActionParameter auto_set_populate = model.addActionParameter2("azexec.auto_set.populate.label", "azexec.auto_set.populate.exec");		
		auto_set_enabled.addEnabledOnSelection(auto_set_action);
		auto_set_enabled.addEnabledOnSelection(auto_set_populate);
		
		ConfigParameterListener cpl = new ConfigParameterListener() {
			@Override
			public void configParameterChanged(ConfigParameter p) {
				if (cfg.hasPluginParameter("auto_set_cmd")) {
					exec_cmd = cfg.getPluginStringParameter("auto_set_cmd");
					auto_set_label.setValue(exec_cmd);
				}
				else {
					exec_cmd = null;
					auto_set_label.setLabelText(BLANK_CMD);
				}
			}			
		};
		cfg.getPluginParameter("auto_set_cmd").addConfigParameterListener(cpl);
		cpl.configParameterChanged(null); // This will initialise the config text.
		
		model.createGroup("azexec.auto_set.group", new Parameter[] {auto_set_enabled, auto_set_label, auto_set_action, auto_set_populate});
		
		this.use_runtime_exec_param = model.addBooleanParameter2(
				"use_runtime_exec", "azexec.config.use_runtime_exec", true);

		// Root menu option.
		MenuManager mm = plugin_interface.getUIManager().getMenuManager();
		MenuItem item_root = mm.addMenuItem(MenuManager.MENU_DOWNLOAD_CONTEXT, "azexec.menu");
		item_root.setDisposeWithUIDetach(UIInstance.UIT_SWT);
		item_root.setStyle(MenuItem.STYLE_MENU);
		item_root.addFillListener(this);
		
		// Set command menu option.
		MenuItem item_set = mm.addMenuItem(item_root, "azexec.menu.set_command");
		item_set.addMultiListener(this);
		
		// Test command menu option.
		MenuItem item_test = mm.addMenuItem(item_root, "azexec.menu.test_command");
		
		// Only make it available when there's only one item selected.
		item_test.addFillListener(new MenuItemFillListener() {
			@Override
			public void menuWillBeShown(MenuItem item, Object context) {
				Object[] dls = (Object[])context;
				item.setEnabled(dls.length==1);
				if (item.isEnabled()) {
					// Only allow it to be enabled if it has a command set.
					String command_template = ((Download)dls[0]).getAttribute(attr);
					item.setEnabled(command_template != null);
				}
			}
		});
		
		item_test.addListener(new MenuItemListener() {
			@Override
			public void selected(MenuItem item, Object context) {
				onCompletion((Download)context);
			}
		});
		
		String log_name = plugin_interface.getUtilities().getLocaleUtilities().getLocalisedMessageText("azexec.logview.name");
		this.model = plugin_interface.getUIManager().createBasicPluginViewModel(log_name);
		this.model.getActivity().setVisible(false);
		this.model.getStatus().setVisible(false);
		this.model.getProgress().setVisible(false);
		
		this.attr = plugin_interface.getTorrentManager().getPluginAttribute("command");
		this.ta_cat = plugin_interface.getTorrentManager().getAttribute(TorrentAttribute.TA_CATEGORY);
		this.channel = plugin_interface.getLogger().getChannel("azexec");
		this.model.attachLoggerChannel(channel);
		
		plugin_interface.getDownloadManager().getGlobalDownloadEventNotifier().addCompletionListener(this);
		final DownloadManagerListener dml = new DownloadManagerListener() {
			@Override
			public void downloadAdded(Download d) {
				if (!auto_set_enabled.getValue()) {return;}
				if (d.isComplete()) {return;}
				if (exec_cmd.length() == 0) {exec_cmd = null;}
				d.setAttribute(attr, exec_cmd);
			}
			@Override
			public void downloadRemoved(Download d) {}
		};
		plugin_interface.getDownloadManager().addListener(dml, false);
		
		auto_set_populate.addListener(new ParameterListener() {
			@Override
			public void parameterChanged(Parameter p) {
				Download[] ds = plugin_interface.getDownloadManager().getDownloads();
				for (int i=0; i<ds.length; i++) {dml.downloadAdded(ds[i]);}
			}
		});
		
		auto_set_action.addListener(new ParameterListener() {
			@Override
			public void parameterChanged(Parameter p) {
				chooseExecCommand(new String[]{exec_cmd}, new chooseExecCommandResults() {
					@Override
					public void execCommandChosen(String val) {
						if (val == null) {return;}
						if (val.equals("")) {val = null; cfg.removePluginParameter("auto_set_cmd");}
						else {cfg.setPluginParameter("auto_set_cmd", val);}
						if (val != null) {updateChosenCommand(val);}
					}
				});
			}
		});
		
	}
	
	@Override
	public void onCompletion(Download d) {
		String command_template = d.getAttribute(attr);
		if (command_template == null) {return;}
		
		File save_path = new File(d.getSavePath());
		String command_f, command_d, command_k, 
		command_n = d.getName(), 
		command_l = d.getAttribute(ta_cat),
		command_t = d.getTorrent().getAnnounceURL().getHost(), 
		command_i = ByteFormatter.encodeString( d.getTorrent().getHash());
		
		if (command_l == null) {
			
			try{
				List<Tag> tags = TagManagerFactory.getTagManager().getTagsForTaggable( TagType.TT_DOWNLOAD_MANUAL, PluginCoreUtils.unwrap( d ));
				
				if ( tags.size() > 0 ){
					
					String str = "";
					
					for (Tag t: tags ){
						str += (str.length()==0?"":",") + t.getTagName( true );
					}
					
					command_l = str;
				}
			}catch( Throwable e ){
			}
			
			if (command_l == null) {
				command_l = "Uncategorised";
			}
		}
		
		if (d.getTorrent().isSimpleTorrent()) {
			command_f = save_path.getName();
			command_d = save_path.getParent();
			command_k = "single";
		}
		else {
			command_f = "";
			command_d = save_path.getPath();
			command_k = "multi";
		}
		
		String command_m = d.getTorrentFileName();
		
		List<String>	bits = new ArrayList<>();
		
		Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command_template);
		
		String command_to_run_str = "";
		
		while(m.find()){
			
		    String bit = m.group(1).replace("\"", "");
		
		    bit = bit.replace("%F", command_f);
		    bit = bit.replace("%D", command_d);
		    bit = bit.replace("%N", command_n);
		    bit = bit.replace("%L", command_l);
		    bit = bit.replace("%T", command_t);
		    bit = bit.replace("%I", command_i);
		    bit = bit.replace("%K", command_k);
		    bit = bit.replace("%M", command_m);

		    command_to_run_str += (command_to_run_str.isEmpty()?"":" ") + bit;
		    
		    bits.add( bit );
		}
		
		final String f_command_to_run_str = command_to_run_str;
		
		final String[] command_to_run_array = bits.toArray( new String[ bits.size()]);
		
		plugin_interface.getUtilities().createThread(d.getName() + " exec", new Runnable() {
			@Override
			public void run() {
				channel.log("Executing: \"" + f_command_to_run_str + "\"" );
				boolean use_runtime_exec = use_runtime_exec_param.getValue();
				try {
					if (use_runtime_exec) {
						Runtime.getRuntime().exec(command_to_run_array);
					}
					else {
						plugin_interface.getUtilities().createProcess( null, command_to_run_array, null );
					}
				}
				catch (Throwable t) {
					channel.logAlert("Unable to run \"" + f_command_to_run_str + "\"", t);
				}
			}
		});
	}
	
	@Override
	public void menuWillBeShown(MenuItem item, Object context) {
		boolean has_completed = false, has_incomplete = false;
		Object[] objs = (Object[])context;
		for (int i=0; i<objs.length; i++) {
			boolean is_complete = ((Download)objs[i]).isComplete(false);
			if (is_complete) 
				has_completed = true;
			else
				has_incomplete = true;
		}
		
		item.setVisible(has_incomplete);
		item.setEnabled(has_incomplete && !has_completed);
	}
	
	@Override
	public void selected(MenuItem item, Object context) {
		final Object[] objs = (Object[])context;
		String[] commands = new String[objs.length];
		for (int i=0; i<objs.length; i++) {
			commands[i] = ((Download)objs[i]).getAttribute(this.attr);
		}
		
			// hack - unfortunately for the fancy torrent menu if we run this immediately the UIInputReceiver grabs
			// the menu as the active shell and this causes the receiver to immediately close when the menu closes.
		
		Utils.execSWTThreadLater(
			10,
			new Runnable()
			{
				@Override
				public void run(){

					chooseExecCommand(commands, new chooseExecCommandResults() {
						@Override
						public void execCommandChosen(String cmd) {
							if (cmd == null) {return;} // No input.
							if (cmd.length() == 0) {cmd = null;} // Blank input - remove the attr.
			
							// Set the attribute on all downloads.
							for (int i=0; i<objs.length; i++) {
								((Download)objs[i]).setAttribute(AzExecPlugin.this.attr, cmd);
							}
			
							if (cmd != null) {updateChosenCommand(cmd);}
						}
					});
				}
			});

	}

	public interface chooseExecCommandResults {
		void execCommandChosen(String cmd);
	}

	public void chooseExecCommand(String[] cmd_history, final chooseExecCommandResults r) {
		String attr = null;
		
		for (int i=0; i<cmd_history.length; i++) {
			String this_attr = cmd_history[i];
			if (attr == null) {attr = this_attr;}
			else if (!attr.equals(this_attr)) {
				attr = null; break;
			}
			// Otherwise the value is the same.
		}
		
		// Grab any previously invoked commands.
		String[] history_array = cfg.getPluginStringListParameter(history_attrib);
		
		// Message strings.
		String[] messages = new String[] {
			"azexec.input.message",       "azexec.input.message.sub.d",
			"azexec.input.message.sub.n", "azexec.input.message.sub.f",
			"azexec.input.message.sub.l", "azexec.input.message.sub.t",
			"azexec.input.message.sub.i", "azexec.input.message.sub.k"
		};
		
		UIInputReceiver input = plugin_interface.getUIManager().getInputReceiver();
		input.setTitle("azexec.input.title");
		input.setMessages(messages);
		if (attr != null) {input.setPreenteredText(attr, false);}
		if (input instanceof UISWTInputReceiver) {
			((UISWTInputReceiver)input).setSelectableItems(history_array, -1, true);
		}
		input.prompt(new UIInputReceiverListener() {
			@Override
			public void UIInputReceiverClosed(UIInputReceiver receiver) {
				if (!receiver.hasSubmittedInput()) {
					r.execCommandChosen(null);
					return;
				}

				// Take the entered command, put it at front of the list.
				String cmd_to_use = receiver.getSubmittedInput().trim();
				if (cmd_to_use.length() == 0) {cmd_to_use = null;}

				r.execCommandChosen((cmd_to_use == null) ? "" : cmd_to_use);
			}
		});

	}
	
	public void updateChosenCommand(String cmd_to_use) {
		String[] history_array = cfg.getPluginStringListParameter(history_attrib);
		
		// Now take the command and re-arrange the history items.
		List<String> new_history = new ArrayList<String>(Arrays.asList(history_array));
		new_history.remove(cmd_to_use);
		new_history.add(0, cmd_to_use);
		if (new_history.size() > HISTORY_LIMIT) {new_history = new_history.subList(0, HISTORY_LIMIT);}
		
		String[] new_history_array = new_history.toArray(new String[new_history.size()]);
		cfg.setPluginStringListParameter(history_attrib, new_history_array);
	}
	
}
