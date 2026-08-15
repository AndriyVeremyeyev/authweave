import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AuthWeave",
  description:
    "Evidence-backed identity architecture decisions with deterministic constraints and human review.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
