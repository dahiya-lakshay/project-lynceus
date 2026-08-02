package com.lynceus.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  @AfterEach
  void cleanUp() {
    // Prevent state from this test leaking into other tests running on the same thread.
    TenantContext.clear();
  }

  @Test
  void get_withNoTenantSet_returnsNull() {
    assertNull(TenantContext.get());
  }

  @Test
  void set_thenGet_returnsSameTenant() {
    TenantContext.set("tenant-acme");

    assertEquals("tenant-acme", TenantContext.get());
  }

  @Test
  void clear_afterSet_removesTenant() {
    TenantContext.set("tenant-acme");

    TenantContext.clear();

    assertNull(TenantContext.get());
  }

  @Test
  void set_calledTwice_overwritesPreviousTenant() {
    TenantContext.set("tenant-acme");
    TenantContext.set("tenant-globex");

    assertEquals("tenant-globex", TenantContext.get());
  }
}
