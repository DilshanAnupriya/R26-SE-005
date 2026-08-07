"use client";

import Link from "next/link";
import { Bell } from "lucide-react";
import { useUnreadCount } from "@/features/notifications/hooks";

/**
 * The unread badge.
 *
 * <p>The count lives in an `aria-label` rather than only in the badge text, because a
 * screen reader reading "Notifications 12" without the word "unread" is ambiguous — and
 * because the badge caps at 9+ visually while the label stays exact.</p>
 */
export function NotificationBell({ orgSlug }: { orgSlug: string }) {
  const { data } = useUnreadCount();
  const unread = data?.unread ?? 0;

  return (
    <Link
      href={`/app/${orgSlug}/notifications`}
      aria-label={
        unread === 0
          ? "Notifications, none unread"
          : `Notifications, ${unread} unread`
      }
      className="relative flex size-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <Bell className="size-4" aria-hidden />
      {unread > 0 ? (
        <span
          aria-hidden
          className="absolute -right-0.5 -top-0.5 flex min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-medium text-primary-foreground tabular-nums"
        >
          {unread > 9 ? "9+" : unread}
        </span>
      ) : null}
    </Link>
  );
}
