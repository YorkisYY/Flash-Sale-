"use client";

import { Card, CardBody, CardHeader, Chip, Spinner } from "@heroui/react";
import { useEffect, useState } from "react";
import { ApiError, OrderInfo, fetchOrder } from "@/lib/api";

const TERMINAL: OrderInfo["status"][] = ["PAID", "SHIPPED", "COMPLETED", "EXPIRED", "CANCELLED"];

function statusChip(status: OrderInfo["status"]) {
  switch (status) {
    case "PAID":
    case "SHIPPED":
    case "COMPLETED":
      return <Chip color="success" variant="flat">Paid · {status}</Chip>;
    case "EXPIRED":
      return <Chip color="danger" variant="flat">Payment expired</Chip>;
    case "CANCELLED":
      return <Chip color="default" variant="flat">Cancelled</Chip>;
    case "CREATED":
    default:
      return <Chip color="warning" variant="flat">Awaiting payment</Chip>;
  }
}

export default function ResultPage({
  params,
}: {
  params: { orderId: string };
}) {
  const { orderId } = params;
  const [order, setOrder] = useState<OrderInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    async function tick() {
      try {
        const o = await fetchOrder(orderId);
        if (cancelled) return;
        setOrder(o);
        if (!TERMINAL.includes(o.status)) {
          timer = setTimeout(tick, 2000);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : (e as Error).message);
        }
      }
    }
    tick();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [orderId]);

  if (error) {
    return (
      <main className="mx-auto max-w-xl px-6 py-16">
        <Card>
          <CardBody>
            <p className="text-red-600">Failed to load order: {error}</p>
          </CardBody>
        </Card>
      </main>
    );
  }

  if (!order) {
    return (
      <main className="mx-auto max-w-xl px-6 py-16 flex justify-center">
        <Spinner label="Looking up order..." />
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-xl px-6 py-16">
      <Card shadow="md">
        <CardHeader className="flex justify-between items-start">
          <div>
            <h1 className="text-2xl font-bold">Order #{order.id}</h1>
            <p className="text-sm text-slate-600 mt-1">Product ID: {order.productId}</p>
          </div>
          {statusChip(order.status)}
        </CardHeader>
        <CardBody className="gap-2 text-sm">
          <Row k="Amount" v={`NT$ ${order.amount}`} />
          <Row k="Quantity" v={order.quantity.toString()} />
          <Row k="Recipient" v={order.buyerName} />
          <Row k="Email" v={order.buyerEmail} />
          <Row k="Phone" v={order.buyerPhone} />
          <Row k="Address" v={order.shippingAddress} />
          {order.providerRef && <Row k="Provider txn" v={order.providerRef} />}
          {order.status === "CREATED" && (
            <p className="mt-4 text-xs text-slate-500">Status auto-refreshes every 2 seconds...</p>
          )}
        </CardBody>
      </Card>
    </main>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between border-b border-slate-100 py-1">
      <span className="text-slate-500">{k}</span>
      <span className="font-medium">{v}</span>
    </div>
  );
}
