# BiglyBT-plugin-azexec
Command Runner (plugin ID 'azexec')

This plugin runs a configurable command on completion of a download. 

It also supports invocation as a script from a BiglyBT Tag's "Execute On Assign" action using the script syntax of "plugin( azexec, "command" ) since BiglyBT 2801_B16 and plugin version 1.4.8

Since version 1.4.9 and BiglyBT 2801_B19 it also supports single invocation from a script with multiple downloads selected. In this case the variables expansions result in a list of values, not a single value. For example, if you select two downloads with display names "dn1" and "dn2" and then tag them at the same time the %N variable with be expanded to "dn1 dn2" (no quotes unless you add them. To indicate that you want this new behaviour (as opposed to separate invocations) you need to add a "+" to the plugin id - "plugin( azexec+, "command" )

The command supports the following variable expansions:

* %F - Name of the downloaded file (for single file torrents)
* %D - Directory where files are saved
* %N - Displayed name of the download
* %L - Torrent Category/Label
* %T - Tracker Name
* %I - Hex encoded info-hash
* %K - Kind of torrent (single|multi)
* %M - Full torrent file name
* %P - Save path of the torrent (since 1.4.9)

## Example
Say you want to execute a command when a completed download enters a stopped state. 

Create a new Tag (e.g. go to View->Tags Overview and hit the 'Add Tag...' button top right). 

Go to the settings for the Tag and set its constraint to "isComplete() && isStopped()" - make sure you do this BEFORE setting the Tag's "execute on assign" value unless you want to execute the command on all existing completed and stopped downloads...)

Right-click on the Tag and select "Execute On Assign->Script" and then enter "plugin( azexec, command-to-run  )"
