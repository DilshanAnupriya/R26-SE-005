import { Sidebar } from "@/components/shared/Sidebar";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen bg-background text-foreground antialiased">
      <Sidebar />
      <div className="flex-1 flex flex-col">
        <header className="h-16 border-b border-card-border bg-background/50 backdrop-blur-md sticky top-0 z-10 px-8 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-400">Dashboard</h2>
          <div className="flex items-center space-x-4">
            <div className="px-3 py-1 rounded-full bg-accent/10 border border-accent/20 text-accent text-xs font-medium">
              System Active
            </div>
          </div>
        </header>
        <main className="p-8 overflow-auto">
          {children}
        </main>
      </div>
    </div>
  );
}
