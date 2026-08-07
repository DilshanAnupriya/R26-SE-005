"use client";

import { use, useState } from "react";
import Link from "next/link";
import { Bell, BellOff, CheckCheck } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import {
  useMarkAllRead,
  useMarkNotificationRead,
  useNotificationPreferences,
  useNotifications,
  useUpdateNotificationPreferences,
} from "@/features/notifications/hooks";
import type { Notification } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

function relativeTime(iso: string) {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["second", 60],
    ["minute", 60],
    ["hour", 24],
    ["day", 7],
    ["week", 4.35],
    ["month", 12],
    ["year", Number.POSITIVE_INFINITY],
  ];
  let value = seconds;
  for (const [unit, size] of units) {
    if (Math.abs(value) < size) {
      return new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }).format(
        Math.round(value),
        unit,
      );
    }
    value /= size;
  }
  return new Date(iso).toLocaleDateString();
}

export default function NotificationsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  const { orgSlug } = use(params);
  const [unreadOnly, setUnreadOnly] = useState(false);

  const inbox = useNotifications({ unreadOnly });
  const markRead = useMarkNotificationRead();
  const markAll = useMarkAllRead();

  const items = inbox.data?.content ?? [];

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Things that happened to you or to work you own.
          </p>
        </div>
        <Button
          size="sm"
          variant="secondary"
          disabled={markAll.isPending}
          onClick={() => markAll.mutate()}
        >
          <CheckCheck className="size-3.5" aria-hidden />
          Mark all read
        </Button>
      </div>

      <Tabs defaultValue="inbox">
        <TabsList>
          <TabsTrigger value="inbox">Inbox</TabsTrigger>
          <TabsTrigger value="preferences">Preferences</TabsTrigger>
        </TabsList>

        <TabsContent value="inbox" className="mt-4 space-y-3">
          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={unreadOnly}
              onChange={(event) => setUnreadOnly(event.target.checked)}
            />
            Unread only
          </label>

          {inbox.isPending ? (
            <div className="space-y-2">
              {Array.from({ length: 5 }).map((_, index) => (
                <Skeleton key={index} className="h-16 w-full" />
              ))}
            </div>
          ) : inbox.error ? (
            <ErrorState error={inbox.error} onRetry={() => void inbox.refetch()} />
          ) : items.length === 0 ? (
            <EmptyState
              icon={unreadOnly ? BellOff : Bell}
              title={unreadOnly ? "Nothing unread" : "No notifications yet"}
              description={
                unreadOnly
                  ? "You are all caught up."
                  : "You will hear about mentions, assignments and sprint risks."
              }
            />
          ) : (
            <ul className="divide-y rounded-lg border">
              {items.map((notification) => (
                <li key={notification.id}>
                  <NotificationRow
                    notification={notification}
                    orgSlug={orgSlug}
                    onRead={() => markRead.mutate(notification.id)}
                  />
                </li>
              ))}
            </ul>
          )}
        </TabsContent>

        <TabsContent value="preferences" className="mt-4">
          <PreferencesPanel />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function NotificationRow({
  notification,
  orgSlug,
  onRead,
}: {
  notification: Notification;
  orgSlug: string;
  onRead: () => void;
}) {
  // The backend stores a client-relative path; the org prefix belongs to routing.
  const href = notification.link ? `/app/${orgSlug}${notification.link}` : null;

  const content = (
    <div className="flex items-start gap-3 p-3">
      {!notification.read ? (
        <span className="mt-1.5 size-2 shrink-0 rounded-full bg-primary" aria-hidden />
      ) : (
        <span className="mt-1.5 size-2 shrink-0" aria-hidden />
      )}

      <Avatar className="size-7 shrink-0">
        <AvatarImage src={notification.actorAvatarUrl ?? undefined} alt="" />
        <AvatarFallback className="text-[11px]">
          {initials(notification.actorName)}
        </AvatarFallback>
      </Avatar>

      <div className="min-w-0 flex-1">
        <p className={cn("text-sm", !notification.read && "font-medium")}>{notification.title}</p>
        {notification.body ? (
          <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{notification.body}</p>
        ) : null}
        <p className="mt-1 flex items-center gap-2">
          <time dateTime={notification.createdAt} className="text-[11px] text-muted-foreground">
            {relativeTime(notification.createdAt)}
          </time>
          {notification.priority === "HIGH" || notification.priority === "URGENT" ? (
            <Badge variant="secondary" className="text-[10px]">
              {notification.priority.toLowerCase()}
            </Badge>
          ) : null}
        </p>
      </div>

      {!notification.read ? (
        <Button
          size="sm"
          variant="ghost"
          className="shrink-0"
          onClick={(event) => {
            // Inside a link: marking read must not also navigate.
            event.preventDefault();
            event.stopPropagation();
            onRead();
          }}
        >
          <span className="sr-only">Mark as read</span>
          <CheckCheck className="size-3.5" aria-hidden />
        </Button>
      ) : null}
    </div>
  );

  return href ? (
    <Link
      href={href}
      onClick={() => {
        if (!notification.read) onRead();
      }}
      className="block transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none focus-visible:-outline-offset-2"
    >
      {content}
    </Link>
  ) : (
    content
  );
}

/**
 * Delivery preferences.
 *
 * <p>Saved per toggle rather than behind a Save button. A preferences screen where the
 * change does not stick until you find the button at the bottom is one people leave
 * thinking they turned something off.</p>
 */
function PreferencesPanel() {
  const preferences = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();

  if (preferences.isPending) return <Skeleton className="h-64 w-full" />;
  if (preferences.error) {
    return <ErrorState error={preferences.error} onRetry={() => void preferences.refetch()} />;
  }

  const rows = preferences.data ?? [];

  return (
    <div className="rounded-lg border">
      <table className="w-full text-sm">
        <caption className="sr-only">Notification delivery preferences</caption>
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th scope="col" className="p-2 font-medium">Type</th>
            <th scope="col" className="p-2 text-center font-medium">In app</th>
            <th scope="col" className="p-2 text-center font-medium">Email</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((preference) => (
            <tr key={preference.type} className="border-b last:border-0">
              <td className="p-2">
                <span className="block">{preference.description ?? preference.type}</span>
                <span className="block text-[11px] text-muted-foreground">{preference.type}</span>
              </td>
              {(["inApp", "email"] as const).map((channel) => (
                <td key={channel} className="p-2 text-center">
                  <label className="inline-flex">
                    <span className="sr-only">
                      {channel === "inApp" ? "In-app" : "Email"} for {preference.type}
                    </span>
                    <input
                      type="checkbox"
                      checked={preference[channel]}
                      disabled={update.isPending}
                      onChange={(event) =>
                        update.mutate([
                          {
                            type: preference.type,
                            inApp: channel === "inApp" ? event.target.checked : preference.inApp,
                            email: channel === "email" ? event.target.checked : preference.email,
                            digest: preference.digest,
                          },
                        ])
                      }
                    />
                  </label>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
