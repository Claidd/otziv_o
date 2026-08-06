# Runtime Trust Anchors

These certificates come from the official Gosuslugi certificate page:
`https://www.gosuslugi.ru/crt`.

The Docker build verifies each pinned SHA-256 fingerprint before importing the
certificate into the operating-system and JVM trust stores. Update a pinned
fingerprint only after verifying a replacement through the official source.

## RSA Certificates

`russian_trusted_root_ca.crt`

- Source archive: `linux_russian_trusted_root_ca_pem.zip`
- Source file: `russian_trusted_root_ca_pem.crt`
- SHA-256 fingerprint:
  `D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31`
- Subject/issuer: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Root CA`
- Validity: 2022-03-01 through 2032-02-28

`russian_trusted_sub_ca.crt`

- Source archive: `russian_trusted_sub_ca_pem.zip`
- Source file: `russian_trusted_sub_ca_pem.crt`
- SHA-256 fingerprint:
  `BB:BD:E2:10:3E:79:0B:99:9E:C6:2B:D0:3C:F6:25:A5:A2:E7:C3:16:E1:0A:FE:6A:49:0E:ED:EA:D8:B3:FD:9B`
- Subject: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Sub CA`
- Issuer: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Root CA`
- Validity: 2022-03-01 through 2027-03-06

`russian_trusted_sub_ca_2024.crt`

- Source archive: `russian_trusted_sub_ca_pem.zip`
- Source file: `russian_trusted_sub_ca_2024_pem.crt`
- SHA-256 fingerprint:
  `21:55:78:50:36:C9:00:DB:B5:F1:BB:2A:15:69:C8:0C:55:59:5B:D6:BF:94:86:7A:29:BB:DD:BC:7D:88:A3:F2`
- Subject: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Sub CA`
- Issuer: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Root CA`
- Validity: 2024-07-19 through 2029-07-19

The Gosuslugi archives also include GOST certificates. They are intentionally
not imported into the JVM image here because the current T-Bank TLS chain uses
the RSA certificates above.
