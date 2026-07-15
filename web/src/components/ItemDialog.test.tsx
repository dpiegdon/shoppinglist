import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import ItemDialog from "./ItemDialog";
import type { ItemObject } from "../api/contract";

function registryItem(id: string, name: string, category = "dairy"): ItemObject {
  const clock = { updated_at: 1, updated_by: "dev" };
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: {
      name: { value: name, ...clock },
      category: { value: category, ...clock },
      status: { value: "backlog", ...clock },
      stores: { value: [], ...clock },
      quantity: { value: null, ...clock },
      price: { value: null, ...clock },
      note: { value: null, ...clock },
      deleted: { value: false, ...clock },
    },
  };
}

describe("ItemDialog (add mode)", () => {
  it("shows matching suggestions as the user types", async () => {
    const registry = [registryItem("1", "Milk"), registryItem("2", "Mineral water"), registryItem("3", "Bread")];
    render(
      <ItemDialog
        listId="list-1"
        registryItems={registry}
        defaultCurrency="EUR"
        onClose={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    await userEvent.type(screen.getByLabelText("Name"), "mi");

    expect(screen.getByText("Milk")).toBeInTheDocument();
    expect(screen.getByText("Mineral water")).toBeInTheDocument();
    expect(screen.queryByText("Bread")).not.toBeInTheDocument();
  });

  it("picking a suggestion saves with the existing item's id and status todo", async () => {
    const registry = [registryItem("1", "Milk")];
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ItemDialog
        listId="list-1"
        registryItems={registry}
        defaultCurrency="EUR"
        onClose={vi.fn()}
        onSave={onSave}
      />,
    );

    await userEvent.type(screen.getByLabelText("Name"), "Mi");
    await userEvent.click(await screen.findByText("Milk"));
    await userEvent.click(screen.getByText("Save"));

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ itemId: "1", name: "Milk", status: "todo" }),
    );
  });

  it("typing a brand new name saves with a freshly generated id", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ItemDialog listId="list-1" registryItems={[]} defaultCurrency="EUR" onClose={vi.fn()} onSave={onSave} />,
    );

    await userEvent.type(screen.getByLabelText("Name"), "Brand new item");
    await userEvent.click(screen.getByText("Save"));

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Brand new item", status: "todo" }),
    );
    const call = onSave.mock.calls[0][0];
    expect(call.itemId).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe("ItemDialog add-another mode (T-53)", () => {
  it("saves, clears the form, and keeps the dialog open instead of closing it", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    render(
      <ItemDialog listId="list-1" registryItems={[]} defaultCurrency="EUR" onClose={onClose} onSave={onSave} />,
    );

    await userEvent.type(screen.getByLabelText("Name"), "Milk");
    await userEvent.type(screen.getByLabelText("Category"), "dairy");
    await userEvent.click(screen.getByText("Add another"));

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ name: "Milk", category: "dairy" }));
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Name")).toHaveValue("");
    expect(screen.getByLabelText("Category")).toHaveValue("");
  });

  it("refocuses the name field after saving so another item can be typed immediately", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ItemDialog listId="list-1" registryItems={[]} defaultCurrency="EUR" onClose={vi.fn()} onSave={onSave} />,
    );

    await userEvent.type(screen.getByLabelText("Name"), "Milk");
    await userEvent.click(screen.getByText("Add another"));

    expect(screen.getByLabelText("Name")).toHaveFocus();
  });

  it("clears a matched-existing suggestion pick so the next item isn't bound to the same id", async () => {
    const registry = [registryItem("1", "Milk")];
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ItemDialog listId="list-1" registryItems={registry} defaultCurrency="EUR" onClose={vi.fn()} onSave={onSave} />,
    );
    await userEvent.type(screen.getByLabelText("Name"), "Mi");
    await userEvent.click(await screen.findByText("Milk"));
    await userEvent.click(screen.getByText("Add another"));

    await userEvent.type(screen.getByLabelText("Name"), "Bread");
    await userEvent.click(screen.getByText("Add another"));

    const secondCall = onSave.mock.calls[1][0];
    expect(secondCall.name).toBe("Bread");
    expect(secondCall.itemId).not.toBe("1");
  });

  it("is not shown in edit mode", () => {
    const item = registryItem("1", "Milk");
    render(
      <ItemDialog
        listId="list-1"
        registryItems={[item]}
        editingItem={item}
        defaultCurrency="EUR"
        onClose={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    expect(screen.queryByText("Add another")).not.toBeInTheDocument();
  });
});

describe("ItemDialog (edit mode)", () => {
  it("prefills existing values and offers delete", async () => {
    const item = registryItem("1", "Milk", "dairy");
    const onDelete = vi.fn().mockResolvedValue(undefined);
    render(
      <ItemDialog
        listId="list-1"
        registryItems={[item]}
        editingItem={item}
        defaultCurrency="EUR"
        onClose={vi.fn()}
        onSave={vi.fn()}
        onDelete={onDelete}
      />,
    );

    expect(screen.getByLabelText("Name")).toHaveValue("Milk");
    await userEvent.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("1");
  });

  it("does not show suggestions while editing", async () => {
    const item = registryItem("1", "Milk");
    const other = registryItem("2", "Mineral water");
    render(
      <ItemDialog
        listId="list-1"
        registryItems={[item, other]}
        editingItem={item}
        defaultCurrency="EUR"
        onClose={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    expect(screen.queryByText("Mineral water")).not.toBeInTheDocument();
  });
});
