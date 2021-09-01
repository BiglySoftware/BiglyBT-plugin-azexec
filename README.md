# BiglyBT-plugin-azexec
Command Runner (plugin ID 'azexec')

This plugin runs a configurable command on completion of a download. 

It also supports invocation as a script from a BiglyBT Tag's "Execute On Assign" action using the script syntax of "plugin( azexec, "command" ) since BiglyBT 2801_B16 and plugin version 1.4.8

The command supports the following variable expansions:

* %F - Name of the downloaded file (for single file torrents)
* %D - Directory where files are saved
* %N - Displayed name of the download
* %L - Torrent Category/Label
* %T - Tracker Name
* %I - Hex encoded info-hash
* %K - Kind of torrent (single|multi)
* %M - Full torrent file name
