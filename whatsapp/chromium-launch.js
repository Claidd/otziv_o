"use strict";

const BASE_CHROMIUM_LAUNCH_ARGS = Object.freeze([
  "--disable-dev-shm-usage",
  "--disable-gpu",
  "--no-first-run",
  "--disable-extensions",
]);

function chromiumLaunchArgs(proxyServer) {
  const args = [...BASE_CHROMIUM_LAUNCH_ARGS];
  if (proxyServer) {
    args.push(`--proxy-server=${proxyServer}`);
  }
  return args;
}

module.exports = {
  BASE_CHROMIUM_LAUNCH_ARGS,
  chromiumLaunchArgs,
};
