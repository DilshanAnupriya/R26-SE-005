import Link from "next/link";
import { BrandMark } from "@/components/design/brand";
import { SiteNav } from "@/components/marketing/site-nav";

export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      {/* First tab stop on the page: a keyboard user should not have to traverse the
          whole nav to reach the content. */}
      <a
        href="#content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[100] focus:rounded-md focus:bg-primary focus:px-3 focus:py-2 focus:text-sm focus:text-primary-foreground"
      >
        Skip to content
      </a>

      <SiteNav />
      <main id="content">{children}</main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-10 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-2">
            <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
              <BrandMark size={24} />
              SprintIQ
            </Link>
            <p className="text-xs text-muted-foreground">
              AI-powered agile delivery. Predictions attached to decisions.
            </p>
          </div>

          <div className="flex flex-wrap gap-x-6 gap-y-2 text-xs text-muted-foreground">
            <a href="#models" className="transition-colors hover:text-foreground">
              AI models
            </a>
            <a href="#pricing" className="transition-colors hover:text-foreground">
              Pricing
            </a>
            <Link href="/login" className="transition-colors hover:text-foreground">
              Sign in
            </Link>
            <Link href="/register" className="transition-colors hover:text-foreground">
              Start free
            </Link>
          </div>
        </div>

        <div className="border-t py-4 text-center text-2xs text-muted-foreground">
          © {new Date().getFullYear()} SprintIQ
        </div>
      </footer>
    </>
  );
}
