export async function GET() {
  return Response.json(
    {
      service: "web",
      status: "UP",
    },
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
