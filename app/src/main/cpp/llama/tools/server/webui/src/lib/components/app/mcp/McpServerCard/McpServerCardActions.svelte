<script lang="ts">
	import { Trash2, RefreshCw, Pencil } from '@lucide/svelte';
	import { Button } from '$lib/components/ui/button';

	interface Props {
		isHealthChecking: boolean;
		readOnly?: boolean;
		onEdit: () => void;
		onRefresh: () => void;
		onDelete: () => void;
	}

	let { isHealthChecking, readOnly = false, onEdit, onRefresh, onDelete }: Props = $props();
</script>

<div class="flex shrink-0 items-center gap-1">
	{#if !readOnly}
		<Button variant="ghost" size="icon" class="h-7 w-7" onclick={onEdit} aria-label="Edit">
			<Pencil class="h-3.5 w-3.5" />
		</Button>
	{/if}

	<Button
		variant="ghost"
		size="icon"
		class="h-7 w-7"
		onclick={onRefresh}
		disabled={isHealthChecking}
		aria-label="Refresh"
	>
		<RefreshCw class="h-3.5 w-3.5" />
	</Button>

	{#if !readOnly}
		<Button
			variant="ghost"
			size="icon"
			class="hover:text-destructive-foreground h-7 w-7 text-destructive hover:bg-destructive/10"
			onclick={onDelete}
			aria-label="Delete"
		>
			<Trash2 class="h-3.5 w-3.5" />
		</Button>
	{/if}
</div>
