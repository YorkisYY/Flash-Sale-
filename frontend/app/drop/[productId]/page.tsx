"use client";

import { Button, Card, CardBody, CardHeader, Chip, Spinner } from "@heroui/react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { DropInfo, fetchDrop } from "@/lib/api";

function formatCountdown(ms: number): string {
  if (ms <= 0) return "Open";
  const s = Math.floor(ms / 1000);
  const days = Math.floor(s / 86400);
  const hrs  = Math.floor((s % 86400) / 3600);
  const mins = Math.floor((s % 3600) / 60);
  const secs = s % 60;
  if (days > 0) return `${days}d ${hrs}h ${mins}m ${secs}s`;
  if (hrs > 0)  return `${hrs}h ${mins}m ${secs}s`;
  return `${mins}m ${secs}s`;
}

export default function DropPage({
  params,
}: {
  params: { productId: string };
}) {
  const { productId } = params;
  const router = useRouter();
  const [drop, setDrop] = useState<DropInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<number>(Date.now());

  useEffect(() => {
    fetchDrop(productId)
      .then(setDrop)
      .catch((e: Error) => setError(e.message));
  }, [productId]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  // Auto-refresh stock every 3s so the buyer sees live numbers.
  useEffect(() => {
    if (!drop) return;
    const t = setInterval(() => {
      fetchDrop(productId).then(setDrop).catch(() => {});
    }, 3000);
    return () => clearInterval(t);
  }, [drop, productId]);

  if (error) {
    return (
      <main className="mx-auto max-w-xl px-6 py-16">
        <Card>
          <CardBody>
            <p className="text-red-600">Failed to load product: {error}</p>
          </CardBody>
        </Card>
      </main>
    );
  }

  if (!drop) {
    return (
      <main className="mx-auto max-w-xl px-6 py-16 flex justify-center">
        <Spinner label="Loading..." />
      </main>
    );
  }

  const opensAtMs = new Date(drop.dropStartsAt).getTime();
  const msUntilOpen = opensAtMs - now;
  const isOpen = msUntilOpen <= 0;
  const soldOut = drop.availableStock <= 0;
  const canBuy = isOpen && !soldOut && drop.status === "ACTIVE";

  return (
    <main className="mx-auto max-w-xl px-6 py-16">
      <Card shadow="md">
        <CardHeader className="flex justify-between items-start">
          <div>
            <h1 className="text-2xl font-bold">{drop.name}</h1>
            <p className="text-3xl font-semibold mt-2">NT$ {drop.price}</p>
          </div>
          {soldOut ? (
            <Chip color="danger" variant="flat">Sold out</Chip>
          ) : isOpen ? (
            <Chip color="success" variant="flat">Live</Chip>
          ) : (
            <Chip color="warning" variant="flat">Opens soon</Chip>
          )}
        </CardHeader>
        <CardBody className="gap-4">
          <div className="flex justify-between text-sm text-slate-600">
            <span>In stock</span>
            <span className="font-mono">
              {drop.availableStock} / {drop.totalStock}
            </span>
          </div>

          {!isOpen && (
            <div className="rounded-md bg-amber-50 px-4 py-3 text-amber-900">
              Opens in: <span className="font-mono">{formatCountdown(msUntilOpen)}</span>
            </div>
          )}

          <Button
            color="danger"
            size="lg"
            className="w-full font-bold tracking-widest text-lg"
            isDisabled={!canBuy}
            onPress={() => router.push(`/checkout/${drop.id}`)}
          >
            {soldOut ? "Sold out" : isOpen ? "Buy now" : "Not yet open"}
          </Button>
        </CardBody>
      </Card>
    </main>
  );
}
