#!/usr/bin/env bash

adb shell am broadcast -a io.neocrypto.chat.receiver.DEBUG_ACTION_DUMP_PREFERENCES
