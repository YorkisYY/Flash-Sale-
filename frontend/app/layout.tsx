import "./globals.css";
import type { Metadata } from "next";
import { Providers } from "./providers";

export const metadata: Metadata = {
  title: "Flash Sale",
  description: "Limited drop flash-sale demo",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-Hant" className="light">
      <body className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-200 text-slate-900">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
