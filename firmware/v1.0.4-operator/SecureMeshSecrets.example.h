#pragma once
#include <stdint.h>

/*
  SecureMesh v1.0.4 OPERATOR — local LAB secret template.

  1. Copy this file to SecureMeshSecrets.h.
  2. Replace the placeholder bytes with the 32-byte LAB development group key.
  3. Remove the #error line.
  4. Never commit SecureMeshSecrets.h or a real key.

  Production deployments must not use the shared development group-key model.
*/
#error "Configure a local LAB key in SecureMeshSecrets.h before compiling firmware."

constexpr uint8_t DEVELOPMENT_GROUP_KEY[32] = {
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};
