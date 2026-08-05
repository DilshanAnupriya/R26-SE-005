import { cn } from "@/lib/utils";

export function AuthCard({
  title,
  description,
  children,
  footer,
  className,
}: {
  title: string;
  description?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("rounded-lg border bg-card p-6 shadow-sm", className)}>
      <div className="mb-5 space-y-1.5">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {children}
      {footer ? <div className="mt-5 border-t pt-4 text-center text-sm">{footer}</div> : null}
    </div>
  );
}
