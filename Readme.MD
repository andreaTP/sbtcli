# Sbtcli

An *experimental* CLI on Node for Sbt `1.2.X` written in Scala.js.

Feel free to start using it and report any bug, feature request, please note that it's still under hard development.

## Install

If you have already __npm__ installed in your machine:
```bash
npm install -g sbtcli-poc
```

Or you can download your (alpha) architecture specific binary from the releases:
https://github.com/andreaTP/sbtcli/releases

## How to use

From the base directory of any Sbt 1.2.X project you can type:

```
sbtcli <sbt-command>
```

or simply:

```
sbtcli
```

to access an interactive shell.

First time you spin it up, if Sbt hasn't already started it starts a detached instance of Sbt, to turn it off you should explicitly call:

```
sbtcli shutdown
```

You can tweak the logging level by setting the env variable:

```
SBTCLI_LOGLEVEL
```
or with command line options (takes precedence over env variable), default is to `"info"`.

## [experimental] File watch

File watch option (command line option `--continue` or `-c`) depends on Node.js watch and is available under this restrictions:

https://nodejs.org/docs/latest/api/fs.html#fs_availability

## Limitations

Anything you can think about ...

Among the others, right now you can only execute straight commands.
