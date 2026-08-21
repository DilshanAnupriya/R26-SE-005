import { Sidebar } from "@/components/shared/Sidebar";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen bg-background text-foreground antialiased">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 flex h-16 items-center justify-between border-b border-card-border bg-background/50 px-4 backdrop-blur-md lg:px-8">
          <h2 className="text-lg font-semibold text-slate-400">Dashboard</h2>
          <div className="flex items-center space-x-4">
            <div className="px-3 py-1 rounded-full bg-accent/10 border border-accent/20 text-accent text-xs font-medium">
              System Active
            </div>
          </div>
        </header>
        <main className="min-w-0 overflow-x-hidden p-4 pb-24 lg:p-8">
          {children}
        </main>
      </div>
    </div>
  );
}
