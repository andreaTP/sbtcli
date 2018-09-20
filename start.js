global.require = require;
global.fs = require('fs');
global.child_process = require('child_process');
global.net = require('net');
global.readline = require('readline');

require('./target/scala-2.12/sbtcli-fastopt.js');
