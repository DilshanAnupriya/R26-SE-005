import { Client, type IMessage } from "@stomp/stompjs";

/**
 * A frame from the server. Every publisher wraps its payload the same way, so a
 * subscriber can switch on one field.
 */
export interface RealtimeEvent<T = unknown> {
  event: string;
  at: string;
  data: T;
}

export interface RealtimeOptions {
  /** Base API URL, e.g. `http://localhost:8080/api/v1`. */
  apiUrl: string;
  /** Called on every connect — the token is short-lived, so it cannot be captured once. */
  getAccessToken: () => string | null;
  onConnect?: () => void;
  onDisconnect?: () => void;
}

/**
 * The STOMP connection.
 *
 * <p>One connection per browser tab, shared by every subscriber. A connection per
 * component would mean a board with four panels opening four sockets, each with its own
 * reconnect timer, and the server counting one user as four.</p>
 *
 * <p>The token is read at connect time rather than captured, because access tokens live
 * fifteen minutes and a socket outlives them. On reconnect the client asks again, which
 * is what lets a long-lived board survive a token refresh without the user noticing.</p>
 */
export class RealtimeClient {
  private client: Client | null = null;
  private readonly subscriptions = new Map<string, Set<(event: RealtimeEvent) => void>>();
  private connected = false;

  constructor(private readonly options: RealtimeOptions) {}

  isConnected() {
    return this.connected;
  }

  activate() {
    if (this.client) return;

    // The raw-WebSocket endpoint under the SockJS mount. SockJS's own fallbacks exist
    // for browsers that cannot do WebSocket, which is not a constraint this app has, and
    // its client would add a large dependency to serve that case.
    const brokerURL = this.options.apiUrl
      .replace(/^http/, "ws")
      .replace(/\/api\/v1\/?$/, "")
      .concat("/ws/websocket");

    this.client = new Client({
      brokerURL,
      // Re-read on every (re)connect, so a refreshed token is picked up automatically.
      beforeConnect: () => {
        const token = this.options.getAccessToken();
        this.client!.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      reconnectDelay: 5_000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        this.connected = true;
        // Re-subscribe after a reconnect: the broker has no memory of what this
        // connection was watching, and a silently unsubscribed board looks like a
        // working board that has stopped updating — the worst failure mode available.
        this.subscriptions.forEach((_handlers, destination) => this.openSubscription(destination));
        this.options.onConnect?.();
      },
      onDisconnect: () => {
        this.connected = false;
        this.options.onDisconnect?.();
      },
      onWebSocketClose: () => {
        this.connected = false;
      },
      // Silent in production: a websocket that cannot connect must never take a page
      // down, and the app is fully usable over plain HTTP without it.
      debug: () => {},
    });

    this.client.activate();
  }

  deactivate() {
    this.subscriptions.clear();
    void this.client?.deactivate();
    this.client = null;
    this.connected = false;
  }

  /**
   * Subscribes to a destination. Returns the unsubscribe function.
   *
   * <p>Handlers are multiplexed per destination so two components watching the same
   * board share one broker subscription.</p>
   */
  subscribe<T = unknown>(destination: string, handler: (event: RealtimeEvent<T>) => void) {
    const handlers = this.subscriptions.get(destination) ?? new Set();
    handlers.add(handler as (event: RealtimeEvent) => void);

    const isNew = !this.subscriptions.has(destination);
    this.subscriptions.set(destination, handlers);

    if (isNew && this.connected) {
      this.openSubscription(destination);
    }

    return () => {
      handlers.delete(handler as (event: RealtimeEvent) => void);
      if (handlers.size === 0) {
        this.subscriptions.delete(destination);
        // Only over a live connection. stompjs throws "There is no underlying STOMP
        // connection" otherwise, and from a component's cleanup that took the whole board
        // page down to the error screen whenever the backend restarted. A dropped
        // connection has no subscription left to cancel, and the reconnect only re-opens
        // what is still in `subscriptions`.
        if (this.client?.connected) {
          this.client.unsubscribe(destination);
        }
      }
    };
  }

  private openSubscription(destination: string) {
    // The destination doubles as the subscription id, which is what makes
    // unsubscribe-by-destination possible without tracking broker-assigned ids.
    this.client?.subscribe(destination, (message: IMessage) => this.dispatch(destination, message), {
      id: destination,
    });
  }

  private dispatch(destination: string, message: IMessage) {
    let parsed: RealtimeEvent;
    try {
      parsed = JSON.parse(message.body) as RealtimeEvent;
    } catch {
      // A frame this client cannot read is a contract problem, not a reason to tear the
      // connection down — the next frame may be perfectly fine.
      return;
    }
    this.subscriptions.get(destination)?.forEach((handler) => handler(parsed));
  }
}

/** Destinations, built in one place so a typo cannot silently subscribe to nothing. */
export const destinations = {
  project: (organizationId: string, projectId: string) =>
    `/topic/org.${organizationId}.project.${projectId}`,
  sprint: (organizationId: string, sprintId: string) =>
    `/topic/org.${organizationId}.sprint.${sprintId}`,
  /** Spring routes this against the authenticated principal, so it needs no ids. */
  notifications: () => "/user/queue/notifications",
} as const;
