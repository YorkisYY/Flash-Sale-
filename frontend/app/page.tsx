"use client";

import {
  Button,
  Card,
  CardBody,
  CardHeader,
  Chip,
  Divider,
  Input,
  Spinner,
} from "@heroui/react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  ApiError,
  DropInfo,
  deleteProduct,
  fetchDrops,
  seedProduct,
} from "@/lib/api";

export default function Home() {
  const router = useRouter();
  const [drops, setDrops] = useState<DropInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lookup, setLookup] = useState("");
  const [seeding, setSeeding] = useState(false);
  const [seedName, setSeedName] = useState("Test Drop");
  const [seedPrice, setSeedPrice] = useState("50000");
  const [seedStock, setSeedStock] = useState("10");
  const [seedError, setSeedError] = useState<string | null>(null);

  async function refresh() {
    try {
      setDrops(await fetchDrops());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  async function onSeed(e: React.FormEvent) {
    e.preventDefault();
    setSeeding(true);
    setSeedError(null);
    try {
      // dropStartsAt = 60s ago so the drop is open immediately.
      const past = new Date(Date.now() - 60_000).toISOString();
      const created = await seedProduct({
        name: seedName.trim() || "Test Drop",
        price: Number(seedPrice),
        totalStock: Number(seedStock),
        dropStartsAt: past,
      });
      await refresh();
      // Jump straight to the new drop so you can buy it.
      router.push(`/drop/${created.id}`);
    } catch (e) {
      if (e instanceof ApiError) {
        setSeedError(e.message || e.code);
      } else if (e instanceof Error) {
        setSeedError(e.message);
      }
    } finally {
      setSeeding(false);
    }
  }

  function statusChip(d: DropInfo) {
    const open = new Date(d.dropStartsAt).getTime() <= Date.now();
    if (d.availableStock <= 0) return <Chip color="danger" variant="flat">Sold out</Chip>;
    if (!open) return <Chip color="warning" variant="flat">Not yet open</Chip>;
    if (d.status !== "ACTIVE") return <Chip color="default" variant="flat">{d.status}</Chip>;
    return <Chip color="success" variant="flat">Live</Chip>;
  }

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <header className="mb-8">
        <h1 className="text-4xl font-bold tracking-tight">Flash Sale</h1>
        <p className="mt-2 text-slate-600">All active drops. Click in to buy.</p>
      </header>

      {/* Drop list */}
      <section className="mb-10">
        {error && (
          <Card>
            <CardBody>
              <p className="text-red-600">Failed to load: {error}</p>
            </CardBody>
          </Card>
        )}
        {!error && drops === null && (
          <div className="flex justify-center py-10">
            <Spinner label="Loading drops..." />
          </div>
        )}
        {drops && drops.length === 0 && (
          <Card>
            <CardBody>
              <p className="text-slate-600">No drops yet. Use the form below to seed one.</p>
            </CardBody>
          </Card>
        )}
        {drops && drops.length > 0 && (
          <div className="grid gap-4 md:grid-cols-2">
            {drops.map((d) => (
              <Card key={d.id} shadow="sm">
                <CardHeader className="flex justify-between items-start">
                  <div>
                    <p className="text-xs text-slate-500">#{d.id}</p>
                    <h2 className="text-lg font-semibold">{d.name}</h2>
                    <p className="text-xl font-bold mt-1">NT$ {d.price}</p>
                  </div>
                  {statusChip(d)}
                </CardHeader>
                <CardBody className="gap-3">
                  <div className="flex justify-between text-sm text-slate-600">
                    <span>In stock</span>
                    <span className="font-mono">
                      {d.availableStock} / {d.totalStock}
                    </span>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      as={Link}
                      href={`/drop/${d.id}`}
                      color="danger"
                      variant="flat"
                      className="flex-1 font-bold"
                    >
                      Enter drop
                    </Button>
                    <Button
                      color="default"
                      variant="light"
                      onPress={async () => {
                        if (!confirm(`Delete product #${d.id} "${d.name}"?`)) return;
                        try {
                          const res = await deleteProduct(d.id);
                          await refresh();
                          if (res.mode === "archived") {
                            // soft-deleted because of existing orders; tell the user.
                            alert(`#${d.id} has order references; soft-archived. Order history is kept.`);
                          }
                        } catch (e) {
                          alert(`Delete failed: ${e instanceof Error ? e.message : "?"}`);
                        }
                      }}
                    >
                      Delete
                    </Button>
                  </div>
                </CardBody>
              </Card>
            ))}
          </div>
        )}
      </section>

      <Divider className="my-8" />

      {/* Order lookup */}
      <section className="mb-10">
        <h2 className="text-xl font-semibold mb-3">Look up order</h2>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            const id = lookup.trim();
            if (id) router.push(`/result/${id}`);
          }}
        >
          <Input
            label="Order ID"
            placeholder="e.g. 6"
            value={lookup}
            onValueChange={setLookup}
          />
          <Button type="submit" color="primary" className="self-end h-14">
            Look up
          </Button>
        </form>
      </section>

      <Divider className="my-8" />

      {/* Seed test product — dev convenience for the merchant. */}
      <section>
        <h2 className="text-xl font-semibold mb-3">Seed test product</h2>
        <p className="text-sm text-slate-500 mb-4">
          {"dropStartsAt is set to 60s ago; you'll be taken straight to the new drop."}
        </p>
        <form className="flex flex-col gap-3" onSubmit={onSeed}>
          <Input
            label="Product name"
            value={seedName}
            onValueChange={setSeedName}
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Price (TWD)"
              type="number"
              min={1}
              value={seedPrice}
              onValueChange={setSeedPrice}
            />
            <Input
              label="Stock"
              type="number"
              min={1}
              value={seedStock}
              onValueChange={setSeedStock}
            />
          </div>
          {seedError && (
            <p className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">{seedError}</p>
          )}
          <Button
            type="submit"
            color="primary"
            isLoading={seeding}
            className="font-bold"
          >
            Create + jump to drop
          </Button>
        </form>
      </section>
    </main>
  );
}
