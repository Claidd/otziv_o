# Runtime trust anchors

`russian_trusted_root_ca.crt` is the public Russian Trusted Root CA required by
the MAX API certificate chain.

- Source: `https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt`
- Certificate SHA-256 fingerprint:
  `D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31`
- Subject/issuer: `C=RU, O=The Ministry of Digital Development and Communications, CN=Russian Trusted Root CA`
- Validity: 2022-03-01 through 2032-02-27

The Docker build verifies the fingerprint before importing the certificate into
the operating-system and JVM trust stores. Update the pinned fingerprint only
after verifying a replacement through an official source.
