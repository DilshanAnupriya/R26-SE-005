"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function DeveloperDetailPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace("/allocation?tab=developers");
  }, [router]);

  return (
    <div className="min-h-screen flex items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
    </div>
  );
}
