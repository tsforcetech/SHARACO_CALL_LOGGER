import "./style.css";
export const metadata = {
  title: "CallTrack Pro | Workspace",
  description: "Your team, calls and customer relationships in one workspace.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
