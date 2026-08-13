import { LucideIcon } from "lucide-react";
import { motion } from "framer-motion";

interface FeatureDashboardProps {
  title: string;
  description: string;
  icon: LucideIcon;
  children?: React.ReactNode;
}

export function FeatureDashboard({ title, description, icon: Icon, children }: FeatureDashboardProps) {
  return (
    <div className="space-y-8">
      <div className="flex items-start justify-between">
        <div className="space-y-1">
          <div className="flex items-center space-x-3">
            <div className="p-2 bg-primary/10 rounded-lg">
              <Icon className="w-6 h-6 text-primary" />
            </div>
            <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
          </div>
          <p className="text-slate-400 text-lg">{description}</p>
        </div>
        <div className="flex space-x-3">
          <button className="px-4 py-2 glass glass-hover rounded-lg text-sm font-medium">Export Report</button>
          <button className="px-4 py-2 bg-primary hover:bg-primary-dark rounded-lg text-sm font-medium transition-colors">New Analysis</button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Placeholder cards for member metrics */}
        {[1, 2, 3].map((i) => (
          <div key={i} className="glass p-6 rounded-2xl space-y-4">
            <div className="h-4 w-24 bg-slate-800 rounded animate-pulse" />
            <div className="h-8 w-16 bg-slate-700 rounded animate-pulse" />
            <div className="h-2 w-full bg-slate-800 rounded" />
          </div>
        ))}
      </div>

      <div className="glass rounded-2xl p-8 min-h-[400px] flex items-center justify-center border-dashed border-2 border-card-border">
        <div className="text-center space-y-4">
          <p className="text-slate-500 italic">Component implementation goes here...</p>
          <p className="text-xs text-slate-600 uppercase tracking-widest font-bold">Member Space</p>
        </div>
      </div>
      
      {children}
    </div>
  );
}
