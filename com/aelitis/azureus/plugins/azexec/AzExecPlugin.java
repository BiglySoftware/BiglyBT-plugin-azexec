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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.tag.Tag;
import com.biglybt.core.tag.TagManagerFactory;
import com.biglybt.core.tag.TagType;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.GeneralUtils;
import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.config.ConfigParameter;
import com.biglybt.pif.config.ConfigParameterListener;
import com.biglybt.pif.config.PluginConfigSource;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadCompletionListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.ipc.IPCException;
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

public class AzExecPlugin implements Plugin, DownloadCompletionListener, MenuItemListener, MenuItemFillListener {
	
	private static Object KEY_FIRED = new Object();
	
	private BasicPluginViewModel model;
	private LoggerChannel channel;
	private PluginInterface plugin_interface;
	private PluginConfig cfg;
	private TorrentAttribute attr;
	private TorrentAttribute ta_cat;
	private static int HISTORY_LIMIT = 15;
	private BooleanParameter recheck_wait;
	private BooleanParameter use_runtime_exec_param;
	
	private String exec_cmd;
	
	private AsyncDispatcher	dispatcher = new AsyncDispatcher();
	
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
		
		recheck_wait = model.addBooleanParameter2("recheck_wait", "azexec.recheck.wait", false);

		auto_set_enabled.addEnabledOnSelection(auto_set_action);
		auto_set_enabled.addEnabledOnSelection(auto_set_populate);
		auto_set_enabled.addEnabledOnSelection(recheck_wait);
		
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
		
		model.createGroup(
			"azexec.auto_set.group",
			new Parameter[] {auto_set_enabled, auto_set_label, auto_set_action, auto_set_populate, recheck_wait});
		
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
				onCompletion((Download)context, false);
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
				if (exec_cmd != null && exec_cmd.length() == 0) {exec_cmd = null;}
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
		onCompletion( d, true );
	}
	
	private void onCompletion(Download d, boolean auto ) {
		dispatcher.dispatch(AERunnable.create(()->{
			_onCompletion(d, auto);
		}));
	}
	
		// support for plugin script invocation
	
	public Object
	evalBatchScript(
		Map<String,Object>		args )
	
		throws IPCException
	{
		List<Download>	downloads = (List<Download>)args.get( "downloads" );
		
		if ( downloads == null ){
			
			return( evalScript( args ));
		}
		
		String		script	= (String)args.get( "script" );
		
		script = script.trim();
		
		if ( script.length() > 2 && GeneralUtils.startsWithDoubleQuote( script ) && GeneralUtils.endsWithDoubleQuote(script)){
			
			script = script.substring( 1, script.length()-1 );
			
			script = script.trim();
		}
		
		exec( downloads, script );
		
		return( null );
	}
	
	public Object
	evalScript(
		Map<String,Object>		args )
	
		throws IPCException
	{
		Download	download = (Download)args.get( "download" );
		
		String		script	= (String)args.get( "script" );
		
		script = script.trim();
		
		if ( script.length() > 2 && GeneralUtils.startsWithDoubleQuote( script ) && GeneralUtils.endsWithDoubleQuote(script)){
			
			script = script.substring( 1, script.length()-1 );
			
			script = script.trim();
		}
		
		exec( download, script );
		
		return( null );
	}
	
	private void _onCompletion(Download d, boolean auto ) {
		
		if ( 	d.getFlag( Download.FLAG_LIGHT_WEIGHT ) ||
				d.getFlag( Download.FLAG_LOW_NOISE ) ||
				d.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
			
			return;
		}
		
		DownloadManager dm = PluginCoreUtils.unwrap( d );
		
		try{
			Thread.sleep( 5000 );	// give things time to get organised
					
			while( !dm.isDestroyed()){
		
				if ( dm.getMoveProgress() != null || FileUtil.hasTask( dm )){
				
					channel.log( "Waiting for " + dm.getDisplayName() + " to finish moving" );
					
					Thread.sleep( 5000 );
					
				}else{
					
					break;
				}
			}
			
			if ( recheck_wait.getValue()){
				
				while( !dm.isDestroyed()){
					
					if ( d.isChecking()){
					
						channel.log( "Waiting for " + dm.getDisplayName() + " to finish checking" );
						
						Thread.sleep( 5000 );
						
					}else{
						
						break;
					}
				}
			}
		}catch( Throwable e ){
			
		}

		if ( auto ){
			
			if ( !d.isComplete()){
			
				channel.log( dm.getDisplayName() + " is no longer complete, aborting" );
				
				return;
			}
			
			synchronized( this ){
				
				if ( dm.getUserData( KEY_FIRED ) != null ){
					
					channel.log( dm.getDisplayName() + " has already been actioned, aborting" );
					
					return;
				}
				
				dm.setUserData( KEY_FIRED, "" );
			}
		}
		
		String command_template = d.getAttribute(attr);
		if (command_template == null) {return;}
		
		exec( d, command_template );
	}
	
	private void
	exec(
		Download	download,
		String		command_template )
	{
		exec( Arrays.asList( download ), command_template );
	}
	
	private void
	exec(
		List<Download>	downloads,
		String			command_template )
	{
		int num_dl = downloads.size();
		
		List<String> command_all_p	= new ArrayList<>();
		List<String> command_all_f	= new ArrayList<>();
		List<String> command_all_d	= new ArrayList<>();
		List<String> command_all_n	= new ArrayList<>();
		List<String> command_all_l	= new ArrayList<>();
		List<String> command_all_t	= new ArrayList<>();
		List<String> command_all_i	= new ArrayList<>();
		List<String> command_all_k	= new ArrayList<>();
		List<String> command_all_m	= new ArrayList<>();
		
		for ( Download d: downloads ){
			String command_p = d.getSavePath();
			
			File save_path = new File(command_p);
						
			String command_n = d.getName();
			String command_l = d.getAttribute(ta_cat);
			String command_t = d.getTorrent().getAnnounceURL().getHost(); 
			String command_i = ByteFormatter.encodeString( d.getTorrent().getHash());
							
			try{
				List<Tag> tags = TagManagerFactory.getTagManager().getTagsForTaggable( TagType.TT_DOWNLOAD_MANUAL, PluginCoreUtils.unwrap( d ));
				
				if ( tags.size() > 0 ){
					
					String str = "";
					
					for (Tag t: tags ){
						str += (str.length()==0?"":",") + t.getTagName( true );
					}
					
					if ( command_l == null ){
						
						command_l = str;
						
					}else{
						
						command_l += "," + str;
					}
				}
			}catch( Throwable e ){
			}
			
			if (command_l == null) {
				command_l = "Uncategorised";
			}
			
			String command_f, command_d, command_k;

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
			
			command_all_p.add( command_p );
			command_all_f.add( command_f );
			command_all_d.add( command_d );
			command_all_n.add( command_n );
			command_all_l.add( command_l );
			command_all_t.add( command_t );
			command_all_i.add( command_i );
			command_all_k.add( command_k );
			command_all_m.add( command_m );
		}
		
		List<String>	args = new ArrayList<>();
		
		Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command_template);
				
		while(m.find()){
			
		    String bit = m.group(1).replace("\"", "");
				    
		    bit = replaceVar( bit, "%P", command_all_p, args );
		    bit = replaceVar( bit, "%F", command_all_f, args );
		    bit = replaceVar( bit, "%D", command_all_d, args );
		    bit = replaceVar( bit, "%N", command_all_n, args );
		    bit = replaceVar( bit, "%L", command_all_l, args );
		    bit = replaceVar( bit, "%T", command_all_t, args );
		    bit = replaceVar( bit, "%I", command_all_i, args );
		    bit = replaceVar( bit, "%K", command_all_k, args );
		    bit = replaceVar( bit, "%M", command_all_m, args );
		    
		    if ( !bit.isEmpty()){
		    	
		    	args.add( bit );
		    }
		}
		
		String command_to_run_str = "";

		for ( String arg: args ){
			
			command_to_run_str += ( command_to_run_str.isEmpty()?"":" ") + arg;
		}
		
		final String f_command_to_run_str = command_to_run_str;
		
		final String[] command_to_run_array = args.toArray( new String[ args.size()]);
		
		String thread_name;
		
		if ( downloads.size() == 1 ){
			thread_name = downloads.get(0).getName();
		}else{
			thread_name = "multi";
		}
		
		plugin_interface.getUtilities().createThread(thread_name + " exec", new Runnable() {
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
	
	private String
	replaceVar(
		String				bit,
		String				var,
		List<String>		vals,
		List<String>		result )
	{
		int 	val_num = vals.size();
		
		if ( bit.isEmpty() || val_num == 0 ){
			
			return( bit );
			
		}else if ( val_num == 1 ){
			
			return( bit.replace(var, vals.get(0)));
			
		}else{
				// multi expand case - exactly quoted var means expand to 
				// separate args, otherwise we expand to one arg with expanded values
			
			if ( bit.equals( var )){
				
				result.addAll( vals );
				
				return( "" );
				
			}else if ( bit.contains( var )){
				
				String str = "";
				
				for (String val: vals ){
					
					str += (str.isEmpty()?"":" ") + val;
				}
				
				return( bit.replace( var, str ));
				
			}else{
				
				return( bit );
			}
		}
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
