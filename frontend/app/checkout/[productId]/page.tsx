"use client";

import {
  Button,
  Card,
  CardBody,
  CardHeader,
  Input,
  Radio,
  RadioGroup,
  Textarea,
} from "@heroui/react";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, PaymentProviderName, purchase } from "@/lib/api";

export default function CheckoutPage({
  params,
}: {
  params: { productId: string };
}) {
  const { productId } = params;
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [provider, setProvider] = useState<PaymentProviderName>("STRIPE");
  const [form, setForm] = useState({
    buyerName: "",
    buyerEmail: "",
    buyerPhone: "",
    shippingAddress: "",
  });

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const res = await purchase(productId, {
        quantity: 1,
        buyerName: form.buyerName.trim(),
        buyerEmail: form.buyerEmail.trim(),
        buyerPhone: form.buyerPhone.trim(),
        shippingAddress: form.shippingAddress.trim(),
        provider,
      });

      // ECPay-style auto-submit form (POST redirect into the gateway).
      if (res.formHtml) {
        const w = window.open("", "_self");
        if (w) {
          w.document.open();
          w.document.write(res.formHtml);
          w.document.close();
        }
        return;
      }

      // Stripe Checkout URL.
      if (res.redirectUrl) {
        window.location.href = res.redirectUrl;
        return;
      }

      // No payment session (PayUni skeleton) — drop to the result page so the
      // buyer at least sees their order id.
      router.push(`/result/${res.orderId}`);
    } catch (e) {
      if (e instanceof ApiError && e.code === "SOLD_OUT") {
        setError("Sold out");
      } else if (e instanceof ApiError) {
        setError(e.message || e.code);
      } else if (e instanceof Error) {
        setError(e.message);
      } else {
        setError("Unknown error");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto max-w-xl px-6 py-16">
      <Card shadow="md">
        <CardHeader>
          <h1 className="text-2xl font-bold">Checkout</h1>
        </CardHeader>
        <CardBody>
          <form className="flex flex-col gap-4" onSubmit={onSubmit}>
            <Input
              isRequired
              label="Name"
              value={form.buyerName}
              onValueChange={(v) => setForm({ ...form, buyerName: v })}
            />
            <Input
              isRequired
              type="email"
              label="Email"
              value={form.buyerEmail}
              onValueChange={(v) => setForm({ ...form, buyerEmail: v })}
            />
            <Input
              isRequired
              label="Phone"
              value={form.buyerPhone}
              onValueChange={(v) => setForm({ ...form, buyerPhone: v })}
            />
            <Textarea
              isRequired
              label="Shipping address"
              value={form.shippingAddress}
              onValueChange={(v) => setForm({ ...form, shippingAddress: v })}
            />

            <RadioGroup
              label="Payment method"
              orientation="horizontal"
              value={provider}
              onValueChange={(v) => setProvider(v as PaymentProviderName)}
            >
              <Radio value="STRIPE">Stripe</Radio>
              <Radio value="ECPAY">ECPay</Radio>
              <Radio value="PAYUNI">PayUni</Radio>
            </RadioGroup>

            {error && (
              <p className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            <Button
              type="submit"
              color="danger"
              size="lg"
              isLoading={submitting}
              className="font-bold tracking-widest"
            >
              Proceed to payment
            </Button>
          </form>
        </CardBody>
      </Card>
    </main>
  );
}
