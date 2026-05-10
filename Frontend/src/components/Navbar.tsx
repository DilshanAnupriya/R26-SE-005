"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { 
  Bot, 
  Users, 
  BarChart3, 
  Home, 
  ShieldAlert, 
  Zap,
  Menu,
  X,
  Layout,
  Wand2
} from "lucide-react";
import { useState } from "react";

const navItems = [
  { name: "Overview", href: "/", icon: Home },
  { name: "Sprint Board", href: "/sprint-board", icon: Layout },
  { name: "AI Allocation", href: "/ai-allocation", icon: Bot },
  { name: "Developers", href: "/developers", icon: Users },
  { name: "Simulator", href: "/simulator", icon: Wand2 },
  { name: "Analytics", href: "/analytics", icon: BarChart3 },
  { name: "Risk Analysis", href: "/risk", icon: ShieldAlert },
];

export default function Navbar() {
  const pathname = usePathname();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  return (
    <header className="fixed top-0 left-0 right-0 z-50 px-6 py-4">
      <nav className="max-w-7xl mx-auto">
        <div className="glass rounded-2xl border border-card-border px-6 py-3 flex items-center justify-between shadow-2xl backdrop-blur-xl bg-background/40">
          {/* Logo */}
          <Link href="/" className="flex items-center gap-2 group">
            <div className="w-10 h-10 rounded-xl bg-primary/20 flex items-center justify-center border border-primary/30 group-hover:bg-primary/30 transition-all">
              <Zap className="w-6 h-6 text-primary fill-primary/20" />
            </div>
            <div className="hidden sm:block">
              <span className="text-lg font-bold tracking-tight text-foreground">AgileAI</span>
              <span className="text-xs block text-slate-500 font-medium -mt-1 uppercase tracking-widest">Optimizer</span>
            </div>
          </Link>

          {/* Desktop Navigation */}
          <ul className="hidden md:flex items-center gap-1">
            {navItems.map((item) => {
              const isActive = pathname === item.href;
              return (
                <li key={item.name}>
                  <Link 
                    href={item.href}
                    className={`relative px-4 py-2 rounded-xl text-sm font-medium transition-all flex items-center gap-2 ${
                      isActive ? "text-primary" : "text-slate-400 hover:text-foreground hover:bg-slate-900/50"
                    }`}
                  >
                    <item.icon className={`w-4 h-4 ${isActive ? "text-primary" : "text-slate-500"}`} />
                    {item.name}
                    {isActive && (
                      <motion.div 
                        layoutId="activeNav"
                        className="absolute bottom-0 left-0 right-0 h-0.5 bg-primary rounded-full shadow-[0_0_8px_rgba(var(--primary-rgb),0.5)]"
                      />
                    )}
                  </Link>
                </li>
              );
            })}
          </ul>

          {/* Action Buttons */}
          <div className="flex items-center gap-3">
            <button className="hidden sm:flex px-4 py-2 rounded-xl text-sm font-semibold bg-slate-900 hover:bg-slate-800 text-white border border-slate-800 transition-all">
              Docs
            </button>
            <button className="px-5 py-2 rounded-xl text-sm font-bold bg-primary hover:bg-primary-dark text-white shadow-lg shadow-primary/25 transition-all">
              Run ML Engine
            </button>
            
            {/* Mobile Menu Toggle */}
            <button 
              className="md:hidden p-2 text-slate-400 hover:text-white transition-colors"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            >
              {mobileMenuOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
            </button>
          </div>
        </div>

        {/* Mobile Navigation */}
        {mobileMenuOpen && (
          <motion.div 
            initial={{ opacity: 0, y: -20 }}
            animate={{ opacity: 1, y: 0 }}
            className="md:hidden mt-4 glass rounded-2xl border border-card-border p-4 shadow-2xl backdrop-blur-xl bg-background/80"
          >
            <ul className="flex flex-col gap-2">
              {navItems.map((item) => {
                const isActive = pathname === item.href;
                return (
                  <li key={item.name}>
                    <Link 
                      href={item.href}
                      onClick={() => setMobileMenuOpen(false)}
                      className={`px-4 py-3 rounded-xl text-sm font-medium transition-all flex items-center gap-3 ${
                        isActive ? "bg-primary/10 text-primary border border-primary/20" : "text-slate-400 hover:bg-slate-900/50"
                      }`}
                    >
                      <item.icon className="w-5 h-5" />
                      {item.name}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </motion.div>
        )}
      </nav>
    </header>
  );
}
