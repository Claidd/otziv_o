export function chromiumLaunchArgs() {
  return [
    "--disable-dev-shm-usage",
    "--disable-quic",
    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
  ];
}
