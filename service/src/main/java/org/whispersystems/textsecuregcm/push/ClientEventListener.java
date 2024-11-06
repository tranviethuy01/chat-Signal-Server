/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.push;

/**
 * A client event listener handles events related to a client's message-retrieval presence. Handler methods are run on
 * dedicated threads and may safely perform blocking operations.
 */
public interface ClientEventListener {

  /**
   * Indicates that a new message is available in the connected client's message queue.
   */
  void handleNewMessageAvailable();

  /**
   * Indicates that the client's presence has been displaced and the listener should close the client's underlying
   * network connection.
   *
   * @param connectedElsewhere if {@code true}, indicates that the client's presence has been displaced by another
   *                           connection from the same client
   */
  void handleConnectionDisplaced(boolean connectedElsewhere);
}