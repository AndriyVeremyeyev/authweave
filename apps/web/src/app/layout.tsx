import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AuthWeave",
  description:
    "Capture identity requirements and download a draft brief. An early preview of an evidence-backed architecture decision workspace.",
  openGraph: {
    title: "AuthWeave · Identity architecture workspace",
    description: "Try the requirements preview. Evidence-backed identity decisions, actively in development.",
    type: "website",
    siteName: "AuthWeave",
  },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
