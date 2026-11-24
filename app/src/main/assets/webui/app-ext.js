/*
 * App extensions for the bundled llama.cpp Web UI (loaded by index.html before bundle.js).
 *
 *  1. Model download confirmation + progress: before a request that would make the server
 *     download a model (chat / completion / model load), ask the user and show the file sizes;
 *     while the download runs, show a progress popup (polls /api/download/status).
 *  2. UI translation: follows the app's display language (/api/ui/language) and translates the
 *     Web UI's fixed English labels (exact text match). Chat content is never translated.
 */
(function () {
	'use strict';

	var LANGS = ['ja', 'fr', 'es', 'pt', 'de', 'it', 'zh', 'ko'];
	var lang = 'en';
	var langIndex = -1;

	function detectBrowserLang() {
		var l = ((navigator.languages && navigator.languages[0]) || navigator.language || 'en').toLowerCase();
		return l.split('-')[0];
	}

	function setLang(l) {
		l = (l || 'en').toLowerCase();
		lang = l;
		langIndex = LANGS.indexOf(l);
		try { document.documentElement.setAttribute('lang', l); } catch (e) {}
	}
	setLang(detectBrowserLang());

	// ------------------------------------------------------------------------------------------
	// Messages used by this script: [en, ja, fr, es, pt, de, it, zh, ko]
	// ------------------------------------------------------------------------------------------
	var M = {
		dlTitle: ['Download model', 'モデルのダウンロード', 'Télécharger le modèle', 'Descargar modelo', 'Baixar modelo', 'Modell herunterladen', 'Scarica modello', '下载模型', '모델 다운로드'],
		dlIntro: ['To use "{model}", the following files will be downloaded:', '「{model}」を使うため、次のファイルをダウンロードします。', 'Pour utiliser « {model} », les fichiers suivants seront téléchargés :', 'Para usar «{model}» se descargarán los siguientes archivos:', 'Para usar "{model}", os seguintes arquivos serão baixados:', 'Für „{model}“ werden folgende Dateien heruntergeladen:', 'Per usare "{model}" verranno scaricati i seguenti file:', '要使用“{model}”，将下载以下文件：', '"{model}"을(를) 사용하려면 다음 파일을 다운로드합니다:'],
		dlTotal: ['Total size', '合計サイズ', 'Taille totale', 'Tamaño total', 'Tamanho total', 'Gesamtgröße', 'Dimensione totale', '总大小', '총 크기'],
		dlUnknown: ['Unknown', '不明', 'Inconnue', 'Desconocido', 'Desconhecido', 'Unbekannt', 'Sconosciuta', '未知', '알 수 없음'],
		dlWifi: ['This uses a large amount of data. Wi-Fi is strongly recommended.', '大容量の通信が発生します。可能な限り Wi-Fi をご利用ください。', 'Cela consomme beaucoup de données. Le Wi-Fi est fortement recommandé.', 'Consume muchos datos. Se recomienda usar Wi-Fi.', 'Isso consome muitos dados. Recomenda-se usar Wi-Fi.', 'Dies benötigt viele Daten. WLAN wird dringend empfohlen.', 'Consuma molti dati. Si consiglia vivamente il Wi-Fi.', '这会产生大量流量，强烈建议使用 Wi-Fi。', '대용량 데이터가 발생합니다. Wi-Fi 사용을 권장합니다.'],
		dlOk: ['Download', 'ダウンロード', 'Télécharger', 'Descargar', 'Baixar', 'Herunterladen', 'Scarica', '下载', '다운로드'],
		cancel: ['Cancel', 'キャンセル', 'Annuler', 'Cancelar', 'Cancelar', 'Abbrechen', 'Annulla', '取消', '취소'],
		dlProgress: ['Downloading model', 'モデルをダウンロード中', 'Téléchargement du modèle', 'Descargando modelo', 'Baixando modelo', 'Modell wird heruntergeladen', 'Download del modello', '正在下载模型', '모델 다운로드 중'],
		dlPreparing: ['Preparing download...', 'ダウンロードを準備中...', 'Préparation du téléchargement...', 'Preparando la descarga...', 'Preparando o download...', 'Download wird vorbereitet...', 'Preparazione del download...', '正在准备下载...', '다운로드 준비 중...'],
		loading: ['Loading model...', 'モデルを読み込み中...', 'Chargement du modèle...', 'Cargando modelo...', 'Carregando modelo...', 'Modell wird geladen...', 'Caricamento del modello...', '正在加载模型...', '모델 로드 중...'],
		hide: ['Hide', '閉じる', 'Masquer', 'Ocultar', 'Ocultar', 'Ausblenden', 'Nascondi', '隐藏', '숨기기']
	};

	function msg(key, vars) {
		var a = M[key];
		var s = a ? (langIndex >= 0 ? a[langIndex + 1] : a[0]) : key;
		if (vars) {
			for (var k in vars) s = s.split('{' + k + '}').join(vars[k]);
		}
		return s;
	}

	function fmtBytes(n) {
		if (typeof n !== 'number' || n < 0) return '?';
		var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0, v = n;
		while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
		return i === 0 ? n + ' B' : (v >= 100 ? v.toFixed(0) : v.toFixed(2)) + ' ' + u[i];
	}

	// ------------------------------------------------------------------------------------------
	// Modal / popup (self-contained, inline-styled)
	// ------------------------------------------------------------------------------------------
	function isDark() {
		var c = document.documentElement.classList;
		if (c.contains('dark')) return true;
		if (c.contains('light')) return false;
		return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
	}

	function makeOverlay() {
		var dark = isDark();
		var overlay = document.createElement('div');
		overlay.setAttribute('data-app-ext', '1');
		overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.45);' +
			'display:flex;align-items:center;justify-content:center;padding:16px;';
		var box = document.createElement('div');
		box.style.cssText = 'max-width:440px;width:100%;border-radius:12px;padding:20px;box-shadow:0 10px 30px rgba(0,0,0,.35);' +
			'font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;' +
			(dark ? 'background:#1f2326;color:#e8e8e8;' : 'background:#fff;color:#1b1f20;');
		overlay.appendChild(box);
		return { overlay: overlay, box: box, dark: dark };
	}

	function button(text, primary, dark) {
		var b = document.createElement('button');
		b.type = 'button';
		b.textContent = text;
		b.style.cssText = 'border:0;border-radius:8px;padding:8px 14px;font-weight:600;cursor:pointer;margin-left:8px;' +
			(primary ? 'background:#ff8236;color:#fff;' : (dark ? 'background:#33393d;color:#e8e8e8;' : 'background:#eceff1;color:#1b1f20;'));
		return b;
	}

	function confirmDownload(info) {
		return new Promise(function (resolve) {
			var o = makeOverlay();
			var h = document.createElement('div');
			h.textContent = msg('dlTitle');
			h.style.cssText = 'font-size:17px;font-weight:700;margin-bottom:10px;';
			o.box.appendChild(h);
			var p = document.createElement('div');
			p.textContent = msg('dlIntro', { model: info.model || '' });
			o.box.appendChild(p);
			var ul = document.createElement('ul');
			ul.style.cssText = 'margin:8px 0 8px 18px;padding:0;word-break:break-all;';
			(info.files || []).forEach(function (f) {
				var li = document.createElement('li');
				li.textContent = f.name + (f.projector ? ' [mmproj]' : '') + '  (' + fmtBytes(f.size) + ')';
				ul.appendChild(li);
			});
			o.box.appendChild(ul);
			var t = document.createElement('div');
			t.style.cssText = 'font-weight:700;';
			t.textContent = msg('dlTotal') + ': ' + (info.total_bytes >= 0 ? fmtBytes(info.total_bytes) : msg('dlUnknown'));
			o.box.appendChild(t);
			var w = document.createElement('div');
			w.style.cssText = 'margin-top:8px;opacity:.8;font-size:13px;';
			w.textContent = msg('dlWifi');
			o.box.appendChild(w);
			var row = document.createElement('div');
			row.style.cssText = 'display:flex;justify-content:flex-end;margin-top:16px;';
			var c = button(msg('cancel'), false, o.dark);
			var ok = button(msg('dlOk'), true, o.dark);
			row.appendChild(c);
			row.appendChild(ok);
			o.box.appendChild(row);
			function done(v) {
				try { o.overlay.remove(); } catch (e) {}
				resolve(v);
			}
			c.onclick = function () { done(false); };
			ok.onclick = function () { done(true); };
			document.body.appendChild(o.overlay);
			ok.focus();
		});
	}

	var progress = {
		pending: 0,
		el: null,
		hidden: false,
		timer: null,
		bar: null,
		fill: null,
		label: null,
		title: null,
		track: function (promise) {
			var self = this;
			self.pending++;
			self.hidden = false;
			self.ensure();
			self.poll();
			var end = function () {
				self.pending = Math.max(0, self.pending - 1);
				if (self.pending === 0) self.close();
			};
			promise.then(end, end);
		},
		ensure: function () {
			if (this.el) return;
			var self = this;
			var o = makeOverlay();
			this.title = document.createElement('div');
			this.title.style.cssText = 'font-size:17px;font-weight:700;margin-bottom:12px;';
			o.box.appendChild(this.title);
			this.bar = document.createElement('div');
			this.bar.style.cssText = 'height:8px;border-radius:4px;overflow:hidden;' + (o.dark ? 'background:#33393d;' : 'background:#e3e7ea;');
			this.fill = document.createElement('div');
			this.fill.style.cssText = 'height:100%;width:0;background:#ff8236;transition:width .3s;';
			this.bar.appendChild(this.fill);
			o.box.appendChild(this.bar);
			this.label = document.createElement('div');
			this.label.style.cssText = 'margin-top:10px;word-break:break-all;white-space:pre-line;';
			o.box.appendChild(this.label);
			var row = document.createElement('div');
			row.style.cssText = 'display:flex;justify-content:flex-end;margin-top:14px;';
			var hide = button(msg('hide'), false, o.dark);
			hide.onclick = function () { self.hidden = true; self.el.style.display = 'none'; };
			row.appendChild(hide);
			o.box.appendChild(row);
			this.el = o.overlay;
			this.render(null);
			document.body.appendChild(this.el);
		},
		render: function (st) {
			if (!this.el) return;
			if (st && st.active) {
				this.title.textContent = msg('dlProgress');
				var pct = Math.max(0, Math.min(100, st.percent || 0));
				this.fill.style.width = pct + '%';
				var line = (st.file || '') + '\n' + pct + '%';
				if (st.total_bytes > 0) line += '  (' + fmtBytes(st.downloaded_bytes) + ' / ' + fmtBytes(st.total_bytes) + ')';
				this.label.textContent = line;
				this.seenActive = true;
			} else if (this.seenActive) {
				this.title.textContent = msg('loading');
				this.fill.style.width = '100%';
				this.label.textContent = '';
			} else {
				this.title.textContent = msg('dlProgress');
				this.label.textContent = msg('dlPreparing');
			}
			this.el.style.display = this.hidden ? 'none' : 'flex';
		},
		poll: function () {
			var self = this;
			if (self.timer) return;
			var tick = function () {
				if (!self.el) { self.timer = null; return; }
				origFetch('/api/download/status', { cache: 'no-store' })
					.then(function (r) { return r.json(); })
					.then(function (st) { self.render(st); })
					.catch(function () {})
					.then(function () { self.timer = self.el ? setTimeout(tick, 800) : null; });
			};
			self.timer = setTimeout(tick, 200);
		},
		close: function () {
			if (this.timer) { clearTimeout(this.timer); this.timer = null; }
			if (this.el) { try { this.el.remove(); } catch (e) {} }
			this.el = null;
			this.seenActive = false;
		}
	};

	// ------------------------------------------------------------------------------------------
	// fetch wrapper: confirm before a request that would download a model
	// ------------------------------------------------------------------------------------------
	var origFetch = window.fetch.bind(window);
	var MODEL_PATHS = /\/(v1\/chat\/completions|v1\/completions|v1\/responses|v1\/messages|api\/chat|api\/generate|models\/load)$/;
	var confirmed = {};      // model -> true once the user agreed in this page session
	var inflightCheck = {};  // model -> Promise<boolean> (dedupe concurrent checks)

	function CancelledError() {
		try { return new DOMException('Model download cancelled', 'AbortError'); } catch (e) {
			var err = new Error('Model download cancelled'); err.name = 'AbortError'; return err;
		}
	}

	function checkModel(model) {
		var key = model || '';
		if (inflightCheck[key]) return inflightCheck[key];
		var p = origFetch('/api/download/check?model=' + encodeURIComponent(key), { cache: 'no-store' })
			.then(function (r) { return r.ok ? r.json() : null; })
			.catch(function () { return null; })
			.then(function (info) {
				if (!info) return { proceed: true, track: false };
				if (!info.needs_download) return { proceed: true, track: !!info.download_active };
				if (confirmed[info.model] || info.download_active) return { proceed: true, track: true };
				return confirmDownload(info).then(function (ok) {
					if (ok) confirmed[info.model] = true;
					return { proceed: ok, track: ok };
				});
			});
		inflightCheck[key] = p;
		p.then(function () { delete inflightCheck[key]; }, function () { delete inflightCheck[key]; });
		return p;
	}

	window.fetch = function (input, init) {
		var self = this, args = arguments;
		var url, method, body;
		try {
			var isReq = typeof Request !== 'undefined' && input instanceof Request;
			url = new URL(isReq ? input.url : String(input), location.href);
			method = String((init && init.method) || (isReq && input.method) || 'GET').toUpperCase();
			body = init && init.body;
		} catch (e) {
			return origFetch.apply(self, args);
		}
		if (method !== 'POST' || url.origin !== location.origin || !MODEL_PATHS.test(url.pathname)) {
			return origFetch.apply(self, args);
		}
		var model = '';
		if (typeof body === 'string') {
			try { model = (JSON.parse(body) || {}).model || ''; } catch (e) {}
		}
		return checkModel(model).then(function (res) {
			if (!res.proceed) throw CancelledError();
			var p = origFetch.apply(self, args);
			if (res.track) progress.track(p);
			return p;
		});
	};

	// ------------------------------------------------------------------------------------------
	// UI translation (exact-match dictionary; order: ja, fr, es, pt, de, it, zh, ko)
	// ------------------------------------------------------------------------------------------
	var D = {
		'New chat': ['新しいチャット', 'Nouvelle discussion', 'Nuevo chat', 'Novo chat', 'Neuer Chat', 'Nuova chat', '新聊天', '새 채팅'],
		'Search conversations...': ['会話を検索...', 'Rechercher des conversations...', 'Buscar conversaciones...', 'Pesquisar conversas...', 'Unterhaltungen durchsuchen...', 'Cerca conversazioni...', '搜索对话...', '대화 검색...'],
		'Search': ['検索', 'Rechercher', 'Buscar', 'Pesquisar', 'Suchen', 'Cerca', '搜索', '검색'],
		'Settings': ['設定', 'Paramètres', 'Configuración', 'Configurações', 'Einstellungen', 'Impostazioni', '设置', '설정'],
		'Type a message...': ['メッセージを入力...', 'Saisissez un message...', 'Escribe un mensaje...', 'Digite uma mensagem...', 'Nachricht eingeben...', 'Scrivi un messaggio...', '输入消息...', '메시지 입력...'],
		'Ask anything...': ['何でも聞いてください...', 'Demandez ce que vous voulez...', 'Pregunta lo que quieras...', 'Pergunte qualquer coisa...', 'Frag mich etwas...', 'Chiedi qualsiasi cosa...', '有什么想问的...', '무엇이든 물어보세요...'],
		'Send': ['送信', 'Envoyer', 'Enviar', 'Enviar', 'Senden', 'Invia', '发送', '보내기'],
		'Stop': ['停止', 'Arrêter', 'Detener', 'Parar', 'Stopp', 'Interrompi', '停止', '중지'],
		'Stop generation': ['生成を停止', 'Arrêter la génération', 'Detener la generación', 'Parar a geração', 'Generierung stoppen', 'Interrompi la generazione', '停止生成', '생성 중지'],
		'Copy': ['コピー', 'Copier', 'Copiar', 'Copiar', 'Kopieren', 'Copia', '复制', '복사'],
		'Copy code': ['コードをコピー', 'Copier le code', 'Copiar código', 'Copiar código', 'Code kopieren', 'Copia codice', '复制代码', '코드 복사'],
		'Copy content': ['内容をコピー', 'Copier le contenu', 'Copiar contenido', 'Copiar conteúdo', 'Inhalt kopieren', 'Copia contenuto', '复制内容', '내용 복사'],
		'Download content': ['内容をダウンロード', 'Télécharger le contenu', 'Descargar contenido', 'Baixar conteúdo', 'Inhalt herunterladen', 'Scarica contenuto', '下载内容', '내용 다운로드'],
		'Preview code': ['コードをプレビュー', 'Aperçu du code', 'Vista previa del código', 'Pré-visualizar código', 'Code-Vorschau', 'Anteprima codice', '预览代码', '코드 미리보기'],
		'Edit': ['編集', 'Modifier', 'Editar', 'Editar', 'Bearbeiten', 'Modifica', '编辑', '편집'],
		'Delete': ['削除', 'Supprimer', 'Eliminar', 'Excluir', 'Löschen', 'Elimina', '删除', '삭제'],
		'Delete All': ['すべて削除', 'Tout supprimer', 'Eliminar todo', 'Excluir tudo', 'Alle löschen', 'Elimina tutto', '全部删除', '모두 삭제'],
		'Regenerate': ['再生成', 'Régénérer', 'Regenerar', 'Gerar novamente', 'Neu generieren', 'Rigenera', '重新生成', '다시 생성'],
		'Reasoning': ['思考', 'Raisonnement', 'Razonamiento', 'Raciocínio', 'Denkprozess', 'Ragionamento', '推理', '추론'],
		'Reasoning...': ['思考中...', 'Raisonnement...', 'Razonando...', 'Raciocinando...', 'Denkt nach...', 'Ragionamento...', '推理中...', '추론 중...'],
		'Processing...': ['処理中...', 'Traitement...', 'Procesando...', 'Processando...', 'Verarbeitung...', 'Elaborazione...', '处理中...', '처리 중...'],
		'Cancel': ['キャンセル', 'Annuler', 'Cancelar', 'Cancelar', 'Abbrechen', 'Annulla', '取消', '취소'],
		'Save': ['保存', 'Enregistrer', 'Guardar', 'Salvar', 'Speichern', 'Salva', '保存', '저장'],
		'Continue': ['続行', 'Continuer', 'Continuar', 'Continuar', 'Weiter', 'Continua', '继续', '계속'],
		'Close': ['閉じる', 'Fermer', 'Cerrar', 'Fechar', 'Schließen', 'Chiudi', '关闭', '닫기'],
		'Close preview': ['プレビューを閉じる', "Fermer l'aperçu", 'Cerrar vista previa', 'Fechar pré-visualização', 'Vorschau schließen', 'Chiudi anteprima', '关闭预览', '미리보기 닫기'],
		'Save settings': ['設定を保存', 'Enregistrer les paramètres', 'Guardar configuración', 'Salvar configurações', 'Einstellungen speichern', 'Salva impostazioni', '保存设置', '설정 저장'],
		'Reset to default': ['既定値に戻す', 'Rétablir la valeur par défaut', 'Restablecer predeterminado', 'Restaurar padrão', 'Auf Standard zurücksetzen', 'Ripristina predefinito', '恢复默认', '기본값으로 재설정'],
		'Reset to Default': ['既定値に戻す', 'Rétablir les valeurs par défaut', 'Restablecer predeterminados', 'Restaurar padrão', 'Auf Standard zurücksetzen', 'Ripristina predefiniti', '恢复默认', '기본값으로 재설정'],
		'Reset Settings to Default': ['設定を既定値に戻す', 'Réinitialiser les paramètres', 'Restablecer la configuración', 'Restaurar configurações padrão', 'Einstellungen zurücksetzen', 'Ripristina le impostazioni', '将设置恢复为默认', '설정을 기본값으로 재설정'],
		'General': ['一般', 'Général', 'General', 'Geral', 'Allgemein', 'Generale', '常规', '일반'],
		'Display': ['表示', 'Affichage', 'Visualización', 'Exibição', 'Anzeige', 'Visualizzazione', '显示', '표시'],
		'Sampling': ['サンプリング', 'Échantillonnage', 'Muestreo', 'Amostragem', 'Sampling', 'Campionamento', '采样', '샘플링'],
		'Penalties': ['ペナルティ', 'Pénalités', 'Penalizaciones', 'Penalidades', 'Strafen', 'Penalità', '惩罚', '페널티'],
		'Import/Export': ['インポート/エクスポート', 'Importer/Exporter', 'Importar/Exportar', 'Importar/Exportar', 'Import/Export', 'Importa/Esporta', '导入/导出', '가져오기/내보내기'],
		'Developer': ['開発者', 'Développeur', 'Desarrollador', 'Desenvolvedor', 'Entwickler', 'Sviluppatore', '开发者', '개발자'],
		'Theme': ['テーマ', 'Thème', 'Tema', 'Tema', 'Design', 'Tema', '主题', '테마'],
		'Light': ['ライト', 'Clair', 'Claro', 'Claro', 'Hell', 'Chiaro', '浅色', '라이트'],
		'Dark': ['ダーク', 'Sombre', 'Oscuro', 'Escuro', 'Dunkel', 'Scuro', '深色', '다크'],
		'System': ['システム', 'Système', 'Sistema', 'Sistema', 'System', 'Sistema', '系统', '시스템'],
		'System Message': ['システムメッセージ', 'Message système', 'Mensaje del sistema', 'Mensagem do sistema', 'Systemnachricht', 'Messaggio di sistema', '系统消息', '시스템 메시지'],
		'API Key': ['API キー', 'Clé API', 'Clave API', 'Chave de API', 'API-Schlüssel', 'Chiave API', 'API 密钥', 'API 키'],
		'Temperature': ['温度 (Temperature)', 'Température', 'Temperatura', 'Temperatura', 'Temperatur', 'Temperatura', '温度', '온도'],
		'Max tokens': ['最大トークン数', 'Jetons max.', 'Tokens máximos', 'Máximo de tokens', 'Max. Tokens', 'Token massimi', '最大令牌数', '최대 토큰 수'],
		'Samplers': ['サンプラー', 'Échantillonneurs', 'Muestreadores', 'Amostradores', 'Sampler', 'Campionatori', '采样器', '샘플러'],
		'Frequency penalty': ['頻度ペナルティ', 'Pénalité de fréquence', 'Penalización por frecuencia', 'Penalidade de frequência', 'Häufigkeitsstrafe', 'Penalità di frequenza', '频率惩罚', '빈도 페널티'],
		'Presence penalty': ['存在ペナルティ', 'Pénalité de présence', 'Penalización por presencia', 'Penalidade de presença', 'Präsenzstrafe', 'Penalità di presenza', '存在惩罚', '존재 페널티'],
		'Repeat penalty': ['繰り返しペナルティ', 'Pénalité de répétition', 'Penalización por repetición', 'Penalidade de repetição', 'Wiederholungsstrafe', 'Penalità di ripetizione', '重复惩罚', '반복 페널티'],
		'Repeat last N': ['繰り返し判定範囲 (last N)', 'Répétition des N derniers', 'Repetición últimos N', 'Repetição dos últimos N', 'Wiederholung letzte N', 'Ripetizione ultimi N', '重复检测范围 (last N)', '반복 범위 (last N)'],
		'Dynamic temperature range': ['動的温度の範囲', 'Plage de température dynamique', 'Rango de temperatura dinámica', 'Intervalo de temperatura dinâmica', 'Bereich der dynamischen Temperatur', 'Intervallo di temperatura dinamica', '动态温度范围', '동적 온도 범위'],
		'Dynamic temperature exponent': ['動的温度の指数', 'Exposant de température dynamique', 'Exponente de temperatura dinámica', 'Expoente de temperatura dinâmica', 'Exponent der dynamischen Temperatur', 'Esponente di temperatura dinamica', '动态温度指数', '동적 온도 지수'],
		'Select model': ['モデルを選択', 'Choisir un modèle', 'Seleccionar modelo', 'Selecionar modelo', 'Modell auswählen', 'Seleziona modello', '选择模型', '모델 선택'],
		'Select Model': ['モデルを選択', 'Choisir un modèle', 'Seleccionar modelo', 'Selecionar modelo', 'Modell auswählen', 'Seleziona modello', '选择模型', '모델 선택'],
		'Select an available model:': ['利用可能なモデルを選択:', 'Choisissez un modèle disponible :', 'Selecciona un modelo disponible:', 'Selecione um modelo disponível:', 'Verfügbares Modell auswählen:', 'Seleziona un modello disponibile:', '选择可用模型：', '사용 가능한 모델 선택:'],
		'Choose a model to use for the conversation': ['この会話で使うモデルを選択', 'Choisissez le modèle à utiliser pour la conversation', 'Elige el modelo para la conversación', 'Escolha o modelo para a conversa', 'Modell für die Unterhaltung wählen', 'Scegli il modello per la conversazione', '选择本次对话使用的模型', '대화에 사용할 모델 선택'],
		'Search models...': ['モデルを検索...', 'Rechercher des modèles...', 'Buscar modelos...', 'Pesquisar modelos...', 'Modelle suchen...', 'Cerca modelli...', '搜索模型...', '모델 검색...'],
		'Load model': ['モデルを読み込む', 'Charger le modèle', 'Cargar modelo', 'Carregar modelo', 'Modell laden', 'Carica modello', '加载模型', '모델 로드'],
		'Unload model': ['モデルを解放', 'Décharger le modèle', 'Descargar de memoria', 'Descarregar modelo', 'Modell entladen', 'Scarica dalla memoria', '卸载模型', '모델 언로드'],
		'Loaded models': ['読み込み済みのモデル', 'Modèles chargés', 'Modelos cargados', 'Modelos carregados', 'Geladene Modelle', 'Modelli caricati', '已加载的模型', '로드된 모델'],
		'Available models': ['利用可能なモデル', 'Modèles disponibles', 'Modelos disponibles', 'Modelos disponíveis', 'Verfügbare Modelle', 'Modelli disponibili', '可用模型', '사용 가능한 모델'],
		'Favorite models': ['お気に入りのモデル', 'Modèles favoris', 'Modelos favoritos', 'Modelos favoritos', 'Favorisierte Modelle', 'Modelli preferiti', '收藏的模型', '즐겨찾는 모델'],
		'Add to favorites': ['お気に入りに追加', 'Ajouter aux favoris', 'Añadir a favoritos', 'Adicionar aos favoritos', 'Zu Favoriten hinzufügen', 'Aggiungi ai preferiti', '添加到收藏', '즐겨찾기에 추가'],
		'Remove from favorites': ['お気に入りから削除', 'Retirer des favoris', 'Quitar de favoritos', 'Remover dos favoritos', 'Aus Favoriten entfernen', 'Rimuovi dai preferiti', '从收藏中移除', '즐겨찾기에서 제거'],
		'Model information': ['モデル情報', 'Informations sur le modèle', 'Información del modelo', 'Informações do modelo', 'Modellinformationen', 'Informazioni sul modello', '模型信息', '모델 정보'],
		'Model Information': ['モデル情報', 'Informations sur le modèle', 'Información del modelo', 'Informações do modelo', 'Modellinformationen', 'Informazioni sul modello', '模型信息', '모델 정보'],
		'Current model details and capabilities': ['現在のモデルの詳細と機能', 'Détails et capacités du modèle actuel', 'Detalles y capacidades del modelo actual', 'Detalhes e recursos do modelo atual', 'Details und Fähigkeiten des aktuellen Modells', 'Dettagli e capacità del modello attuale', '当前模型的详情和功能', '현재 모델의 세부 정보 및 기능'],
		'Model': ['モデル', 'Modèle', 'Modelo', 'Modelo', 'Modell', 'Modello', '模型', '모델'],
		'Parameters': ['パラメータ', 'Paramètres', 'Parámetros', 'Parâmetros', 'Parameter', 'Parametri', '参数', '매개변수'],
		'Context Size': ['コンテキストサイズ', 'Taille du contexte', 'Tamaño del contexto', 'Tamanho do contexto', 'Kontextgröße', 'Dimensione del contesto', '上下文大小', '컨텍스트 크기'],
		'Model Size': ['モデルサイズ', 'Taille du modèle', 'Tamaño del modelo', 'Tamanho do modelo', 'Modellgröße', 'Dimensione del modello', '模型大小', '모델 크기'],
		'Chat Template': ['チャットテンプレート', 'Modèle de discussion', 'Plantilla de chat', 'Modelo de chat', 'Chat-Vorlage', 'Template di chat', '聊天模板', '채팅 템플릿'],
		'Modalities': ['モダリティ', 'Modalités', 'Modalidades', 'Modalidades', 'Modalitäten', 'Modalità', '模态', '모달리티'],
		'Not available': ['利用不可', 'Non disponible', 'No disponible', 'Não disponível', 'Nicht verfügbar', 'Non disponibile', '不可用', '사용 불가'],
		'Failed to load model': ['モデルの読み込みに失敗しました', 'Échec du chargement du modèle', 'No se pudo cargar el modelo', 'Falha ao carregar o modelo', 'Modell konnte nicht geladen werden', 'Impossibile caricare il modello', '模型加载失败', '모델을 불러오지 못했습니다'],
		'Failed to load models': ['モデル一覧の読み込みに失敗しました', 'Échec du chargement des modèles', 'No se pudieron cargar los modelos', 'Falha ao carregar os modelos', 'Modelle konnten nicht geladen werden', 'Impossibile caricare i modelli', '模型列表加载失败', '모델 목록을 불러오지 못했습니다'],
		'Retry loading model': ['モデルの読み込みを再試行', 'Réessayer de charger le modèle', 'Reintentar cargar el modelo', 'Tentar carregar o modelo novamente', 'Modell erneut laden', 'Riprova a caricare il modello', '重试加载模型', '모델 로드 다시 시도'],
		'Loading models…': ['モデルを読み込み中…', 'Chargement des modèles…', 'Cargando modelos…', 'Carregando modelos…', 'Modelle werden geladen…', 'Caricamento modelli…', '正在加载模型…', '모델 로드 중…'],
		'Loading model information...': ['モデル情報を読み込み中...', 'Chargement des informations du modèle...', 'Cargando información del modelo...', 'Carregando informações do modelo...', 'Modellinformationen werden geladen...', 'Caricamento informazioni sul modello...', '正在加载模型信息...', '모델 정보 로드 중...'],
		'No models found.': ['モデルが見つかりません。', 'Aucun modèle trouvé.', 'No se encontraron modelos.', 'Nenhum modelo encontrado.', 'Keine Modelle gefunden.', 'Nessun modello trovato.', '未找到模型。', '모델을 찾을 수 없습니다.'],
		'No models available.': ['利用できるモデルがありません。', 'Aucun modèle disponible.', 'No hay modelos disponibles.', 'Nenhum modelo disponível.', 'Keine Modelle verfügbar.', 'Nessun modello disponibile.', '没有可用的模型。', '사용 가능한 모델이 없습니다.'],
		'No model information available': ['モデル情報がありません', 'Aucune information sur le modèle', 'No hay información del modelo', 'Nenhuma informação do modelo', 'Keine Modellinformationen verfügbar', 'Nessuna informazione sul modello', '没有模型信息', '모델 정보가 없습니다'],
		'Delete Conversation': ['会話を削除', 'Supprimer la conversation', 'Eliminar conversación', 'Excluir conversa', 'Unterhaltung löschen', 'Elimina conversazione', '删除对话', '대화 삭제'],
		'Delete all conversations': ['すべての会話を削除', 'Supprimer toutes les conversations', 'Eliminar todas las conversaciones', 'Excluir todas as conversas', 'Alle Unterhaltungen löschen', 'Elimina tutte le conversazioni', '删除所有对话', '모든 대화 삭제'],
		'Delete All Conversations': ['すべての会話を削除', 'Supprimer toutes les conversations', 'Eliminar todas las conversaciones', 'Excluir todas as conversas', 'Alle Unterhaltungen löschen', 'Elimina tutte le conversazioni', '删除所有对话', '모든 대화 삭제'],
		'Delete Message': ['メッセージを削除', 'Supprimer le message', 'Eliminar mensaje', 'Excluir mensagem', 'Nachricht löschen', 'Elimina messaggio', '删除消息', '메시지 삭제'],
		'Are you sure you want to delete this conversation? This action cannot be undone and will permanently remove all messages in this conversation.': ['この会話を削除しますか？この操作は取り消せず、会話内のすべてのメッセージが完全に削除されます。', 'Voulez-vous vraiment supprimer cette conversation ? Cette action est irréversible et supprimera définitivement tous ses messages.', '¿Seguro que quieres eliminar esta conversación? Esta acción no se puede deshacer y eliminará todos sus mensajes.', 'Tem certeza de que deseja excluir esta conversa? Esta ação não pode ser desfeita e removerá todas as mensagens dela.', 'Diese Unterhaltung wirklich löschen? Dies kann nicht rückgängig gemacht werden und entfernt alle Nachrichten dauerhaft.', 'Eliminare questa conversazione? L\'azione è irreversibile e rimuoverà definitivamente tutti i messaggi.', '确定要删除此对话吗？此操作无法撤销，将永久删除其中的所有消息。', '이 대화를 삭제하시겠습니까? 이 작업은 되돌릴 수 없으며 대화의 모든 메시지가 영구 삭제됩니다.'],
		'Are you sure you want to delete all conversations? This action cannot be undone and will permanently remove all your conversations and messages.': ['すべての会話を削除しますか？この操作は取り消せず、すべての会話とメッセージが完全に削除されます。', 'Voulez-vous vraiment supprimer toutes les conversations ? Cette action est irréversible.', '¿Seguro que quieres eliminar todas las conversaciones? Esta acción no se puede deshacer.', 'Tem certeza de que deseja excluir todas as conversas? Esta ação não pode ser desfeita.', 'Wirklich alle Unterhaltungen löschen? Dies kann nicht rückgängig gemacht werden.', 'Eliminare tutte le conversazioni? L\'azione è irreversibile.', '确定要删除所有对话吗？此操作无法撤销。', '모든 대화를 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.'],
		'Edit Conversation Name': ['会話名を編集', 'Modifier le nom de la conversation', 'Editar nombre de la conversación', 'Editar nome da conversa', 'Namen der Unterhaltung bearbeiten', 'Modifica nome conversazione', '编辑对话名称', '대화 이름 편집'],
		'Conversation Name': ['会話名', 'Nom de la conversation', 'Nombre de la conversación', 'Nome da conversa', 'Name der Unterhaltung', 'Nome conversazione', '对话名称', '대화 이름'],
		'Enter a new name': ['新しい名前を入力', 'Saisissez un nouveau nom', 'Introduce un nuevo nombre', 'Digite um novo nome', 'Neuen Namen eingeben', 'Inserisci un nuovo nome', '输入新名称', '새 이름 입력'],
		'Fork': ['分岐', 'Bifurquer', 'Bifurcar', 'Ramificar', 'Abzweigen', 'Dirama', '分支', '분기'],
		'Fork Conversation': ['会話を分岐', 'Bifurquer la conversation', 'Bifurcar conversación', 'Ramificar conversa', 'Unterhaltung abzweigen', 'Dirama conversazione', '创建对话分支', '대화 분기'],
		'Fork conversation': ['会話を分岐', 'Bifurquer la conversation', 'Bifurcar conversación', 'Ramificar conversa', 'Unterhaltung abzweigen', 'Dirama conversazione', '创建对话分支', '대화 분기'],
		'Enter fork name': ['分岐の名前を入力', 'Nom de la bifurcation', 'Nombre de la bifurcación', 'Nome da ramificação', 'Name der Abzweigung', 'Nome della diramazione', '输入分支名称', '분기 이름 입력'],
		'See parent conversation': ['元の会話を表示', 'Voir la conversation parente', 'Ver conversación original', 'Ver conversa original', 'Ursprüngliche Unterhaltung anzeigen', 'Vedi conversazione originale', '查看原对话', '원래 대화 보기'],
		'Export': ['エクスポート', 'Exporter', 'Exportar', 'Exportar', 'Exportieren', 'Esporta', '导出', '내보내기'],
		'Export Conversations': ['会話をエクスポート', 'Exporter les conversations', 'Exportar conversaciones', 'Exportar conversas', 'Unterhaltungen exportieren', 'Esporta conversazioni', '导出对话', '대화 내보내기'],
		'Import Conversations': ['会話をインポート', 'Importer des conversations', 'Importar conversaciones', 'Importar conversas', 'Unterhaltungen importieren', 'Importa conversazioni', '导入对话', '대화 가져오기'],
		'No conversations available': ['会話がありません', 'Aucune conversation', 'No hay conversaciones', 'Nenhuma conversa', 'Keine Unterhaltungen', 'Nessuna conversazione', '没有对话', '대화가 없습니다'],
		'Discard': ['破棄', 'Abandonner', 'Descartar', 'Descartar', 'Verwerfen', 'Scarta', '放弃', '버리기'],
		'Discard changes?': ['変更を破棄しますか？', 'Abandonner les modifications ?', '¿Descartar cambios?', 'Descartar alterações?', 'Änderungen verwerfen?', 'Scartare le modifiche?', '放弃更改？', '변경 사항을 삭제할까요?'],
		'Keep editing': ['編集を続ける', 'Continuer à modifier', 'Seguir editando', 'Continuar editando', 'Weiter bearbeiten', 'Continua a modificare', '继续编辑', '계속 편집'],
		'You have unsaved changes. Are you sure you want to discard them?': ['保存されていない変更があります。破棄しますか？', 'Vous avez des modifications non enregistrées. Voulez-vous les abandonner ?', 'Tienes cambios sin guardar. ¿Quieres descartarlos?', 'Há alterações não salvas. Deseja descartá-las?', 'Es gibt ungespeicherte Änderungen. Verwerfen?', 'Ci sono modifiche non salvate. Vuoi scartarle?', '有未保存的更改。确定要放弃吗？', '저장되지 않은 변경 사항이 있습니다. 삭제하시겠습니까?'],
		'Edit your message...': ['メッセージを編集...', 'Modifiez votre message...', 'Edita tu mensaje...', 'Edite sua mensagem...', 'Nachricht bearbeiten...', 'Modifica il messaggio...', '编辑你的消息...', '메시지 편집...'],
		'Update without re-sending': ['再送信せずに更新', 'Mettre à jour sans renvoyer', 'Actualizar sin reenviar', 'Atualizar sem reenviar', 'Aktualisieren ohne erneutes Senden', 'Aggiorna senza reinviare', '更新但不重新发送', '다시 보내지 않고 업데이트'],
		'Branch conversation after edit': ['編集後に会話を分岐', 'Bifurquer après modification', 'Bifurcar tras editar', 'Ramificar após editar', 'Nach Bearbeitung abzweigen', 'Dirama dopo la modifica', '编辑后创建分支', '편집 후 대화 분기'],
		'Previous version': ['前のバージョン', 'Version précédente', 'Versión anterior', 'Versão anterior', 'Vorherige Version', 'Versione precedente', '上一版本', '이전 버전'],
		'Next version': ['次のバージョン', 'Version suivante', 'Versión siguiente', 'Próxima versão', 'Nächste Version', 'Versione successiva', '下一版本', '다음 버전'],
		'Attach a file': ['ファイルを添付', 'Joindre un fichier', 'Adjuntar un archivo', 'Anexar um arquivo', 'Datei anhängen', 'Allega un file', '附加文件', '파일 첨부'],
		'Add files, system prompt or configure MCP servers': ['ファイル・システムプロンプトの追加や MCP サーバーの設定', 'Ajouter des fichiers, un prompt système ou configurer des serveurs MCP', 'Añadir archivos, prompt del sistema o configurar servidores MCP', 'Adicionar arquivos, prompt do sistema ou configurar servidores MCP', 'Dateien, Systemprompt hinzufügen oder MCP-Server konfigurieren', 'Aggiungi file, prompt di sistema o configura server MCP', '添加文件、系统提示词或配置 MCP 服务器', '파일·시스템 프롬프트 추가 또는 MCP 서버 설정'],
		'Drop your files here to upload': ['ここにファイルをドロップしてアップロード', 'Déposez vos fichiers ici', 'Suelta tus archivos aquí', 'Solte seus arquivos aqui', 'Dateien hier ablegen', 'Trascina qui i file', '将文件拖放到此处上传', '여기에 파일을 놓아 업로드'],
		'File type not supported': ['対応していないファイル形式です', 'Type de fichier non pris en charge', 'Tipo de archivo no compatible', 'Tipo de arquivo não suportado', 'Dateityp nicht unterstützt', 'Tipo di file non supportato', '不支持的文件类型', '지원되지 않는 파일 형식'],
		'Images': ['画像', 'Images', 'Imágenes', 'Imagens', 'Bilder', 'Immagini', '图片', '이미지'],
		'Audio Files': ['音声ファイル', 'Fichiers audio', 'Archivos de audio', 'Arquivos de áudio', 'Audiodateien', 'File audio', '音频文件', '오디오 파일'],
		'PDF Files': ['PDF ファイル', 'Fichiers PDF', 'Archivos PDF', 'Arquivos PDF', 'PDF-Dateien', 'File PDF', 'PDF 文件', 'PDF 파일'],
		'Text Files': ['テキストファイル', 'Fichiers texte', 'Archivos de texto', 'Arquivos de texto', 'Textdateien', 'File di testo', '文本文件', '텍스트 파일'],
		'Image processing requires a vision model': ['画像の処理には Vision 対応モデルが必要です', 'Le traitement des images nécessite un modèle de vision', 'Procesar imágenes requiere un modelo de visión', 'O processamento de imagens requer um modelo de visão', 'Bildverarbeitung erfordert ein Vision-Modell', 'L\'elaborazione delle immagini richiede un modello di visione', '处理图片需要视觉模型', '이미지 처리에는 비전 모델이 필요합니다'],
		'Current model does not support audio': ['現在のモデルは音声に対応していません', 'Le modèle actuel ne prend pas en charge l\'audio', 'El modelo actual no admite audio', 'O modelo atual não suporta áudio', 'Das aktuelle Modell unterstützt kein Audio', 'Il modello attuale non supporta l\'audio', '当前模型不支持音频', '현재 모델은 오디오를 지원하지 않습니다'],
		'Requires vision model': ['Vision 対応モデルが必要', 'Nécessite un modèle de vision', 'Requiere modelo de visión', 'Requer modelo de visão', 'Erfordert Vision-Modell', 'Richiede un modello di visione', '需要视觉模型', '비전 모델 필요'],
		'Requires audio model': ['音声対応モデルが必要', 'Nécessite un modèle audio', 'Requiere modelo de audio', 'Requer modelo de áudio', 'Erfordert Audio-Modell', 'Richiede un modello audio', '需要音频模型', '오디오 모델 필요'],
		'Text-only': ['テキストのみ', 'Texte uniquement', 'Solo texto', 'Somente texto', 'Nur Text', 'Solo testo', '仅文本', '텍스트 전용'],
		'Some files cannot be uploaded with the current model.': ['現在のモデルではアップロードできないファイルがあります。', 'Certains fichiers ne peuvent pas être envoyés avec le modèle actuel.', 'Algunos archivos no se pueden subir con el modelo actual.', 'Alguns arquivos não podem ser enviados com o modelo atual.', 'Einige Dateien können mit dem aktuellen Modell nicht hochgeladen werden.', 'Alcuni file non possono essere caricati con il modello attuale.', '当前模型无法上传部分文件。', '현재 모델로는 업로드할 수 없는 파일이 있습니다.'],
		'File Upload Error': ['ファイルのアップロードエラー', "Erreur d'envoi de fichier", 'Error al subir el archivo', 'Erro ao enviar arquivo', 'Fehler beim Hochladen', 'Errore di caricamento file', '文件上传错误', '파일 업로드 오류'],
		'Error': ['エラー', 'Erreur', 'Error', 'Erro', 'Fehler', 'Errore', '错误', '오류'],
		'Unknown error': ['不明なエラー', 'Erreur inconnue', 'Error desconocido', 'Erro desconhecido', 'Unbekannter Fehler', 'Errore sconosciuto', '未知错误', '알 수 없는 오류'],
		'Unknown error occurred': ['不明なエラーが発生しました', 'Une erreur inconnue est survenue', 'Se produjo un error desconocido', 'Ocorreu um erro desconhecido', 'Ein unbekannter Fehler ist aufgetreten', 'Si è verificato un errore sconosciuto', '发生未知错误', '알 수 없는 오류가 발생했습니다'],
		'Try again': ['再試行', 'Réessayer', 'Reintentar', 'Tentar novamente', 'Erneut versuchen', 'Riprova', '重试', '다시 시도'],
		'Got it': ['OK', 'Compris', 'Entendido', 'Entendi', 'Verstanden', 'Ho capito', '知道了', '확인'],
		'Go Home': ['ホームへ', "Retour à l'accueil", 'Ir al inicio', 'Ir para o início', 'Zur Startseite', 'Vai alla home', '返回首页', '홈으로'],
		'Server Connection Error': ['サーバー接続エラー', 'Erreur de connexion au serveur', 'Error de conexión con el servidor', 'Erro de conexão com o servidor', 'Serververbindungsfehler', 'Errore di connessione al server', '服务器连接错误', '서버 연결 오류'],
		'Server unavailable': ['サーバーを利用できません', 'Serveur indisponible', 'Servidor no disponible', 'Servidor indisponível', 'Server nicht verfügbar', 'Server non disponibile', '服务器不可用', '서버를 사용할 수 없습니다'],
		'Connecting to Server': ['サーバーに接続中', 'Connexion au serveur', 'Conectando con el servidor', 'Conectando ao servidor', 'Verbindung zum Server', 'Connessione al server', '正在连接服务器', '서버에 연결 중'],
		'Check server logs for any error messages': ['サーバーログにエラーがないか確認してください', "Consultez les journaux du serveur pour d'éventuelles erreurs", 'Revisa los registros del servidor', 'Verifique os logs do servidor', 'Serverprotokolle auf Fehler prüfen', 'Controlla i log del server', '检查服务器日志中的错误信息', '서버 로그에서 오류를 확인하세요'],
		'Verify your network connection': ['ネットワーク接続を確認してください', 'Vérifiez votre connexion réseau', 'Verifica tu conexión de red', 'Verifique sua conexão de rede', 'Netzwerkverbindung prüfen', 'Verifica la connessione di rete', '检查网络连接', '네트워크 연결을 확인하세요'],
		'Troubleshooting': ['トラブルシューティング', 'Dépannage', 'Solución de problemas', 'Solução de problemas', 'Fehlerbehebung', 'Risoluzione dei problemi', '故障排除', '문제 해결'],
		'Toggle Sidebar': ['サイドバーの切り替え', 'Afficher/masquer la barre latérale', 'Mostrar/ocultar barra lateral', 'Alternar barra lateral', 'Seitenleiste umschalten', 'Mostra/nascondi barra laterale', '切换侧边栏', '사이드바 전환'],
		'Sidebar': ['サイドバー', 'Barre latérale', 'Barra lateral', 'Barra lateral', 'Seitenleiste', 'Barra laterale', '侧边栏', '사이드바'],
		'Show raw output': ['生の出力を表示', 'Afficher la sortie brute', 'Mostrar salida sin procesar', 'Mostrar saída bruta', 'Rohausgabe anzeigen', 'Mostra output grezzo', '显示原始输出', '원본 출력 표시'],
		'Response was truncated': ['応答は途中で切り詰められました', 'La réponse a été tronquée', 'La respuesta se truncó', 'A resposta foi truncada', 'Antwort wurde abgeschnitten', 'La risposta è stata troncata', '响应已被截断', '응답이 잘렸습니다'],
		'Show thought in progress': ['思考の途中経過を表示', 'Afficher le raisonnement en cours', 'Mostrar el razonamiento en curso', 'Mostrar raciocínio em andamento', 'Laufenden Denkprozess anzeigen', 'Mostra il ragionamento in corso', '显示进行中的思考', '진행 중인 사고 표시'],
		'Show tool call in progress': ['ツール呼び出しの途中経過を表示', "Afficher l'appel d'outil en cours", 'Mostrar llamada de herramienta en curso', 'Mostrar chamada de ferramenta em andamento', 'Laufenden Tool-Aufruf anzeigen', "Mostra la chiamata dello strumento in corso", '显示进行中的工具调用', '진행 중인 도구 호출 표시'],
		'Show message generation statistics': ['メッセージ生成の統計を表示', 'Afficher les statistiques de génération', 'Mostrar estadísticas de generación', 'Mostrar estatísticas de geração', 'Generierungsstatistik anzeigen', 'Mostra statistiche di generazione', '显示消息生成统计', '메시지 생성 통계 표시'],
		'Keep stats visible after generation': ['生成後も統計を表示したままにする', 'Garder les statistiques après la génération', 'Mantener estadísticas tras la generación', 'Manter estatísticas após a geração', 'Statistik nach der Generierung anzeigen', 'Mantieni le statistiche dopo la generazione', '生成后保持显示统计', '생성 후에도 통계 유지'],
		'Disable automatic scroll': ['自動スクロールを無効化', 'Désactiver le défilement automatique', 'Desactivar desplazamiento automático', 'Desativar rolagem automática', 'Automatisches Scrollen deaktivieren', 'Disattiva scorrimento automatico', '禁用自动滚动', '자동 스크롤 끄기'],
		'Render user content as Markdown': ['ユーザーの入力を Markdown で表示', 'Afficher le contenu utilisateur en Markdown', 'Mostrar contenido del usuario como Markdown', 'Exibir conteúdo do usuário como Markdown', 'Benutzerinhalt als Markdown darstellen', 'Mostra contenuto utente come Markdown', '以 Markdown 渲染用户内容', '사용자 입력을 Markdown으로 표시'],
		'Use full height code blocks': ['コードブロックを全高で表示', 'Blocs de code en pleine hauteur', 'Bloques de código a altura completa', 'Blocos de código em altura total', 'Codeblöcke in voller Höhe', 'Blocchi di codice a tutta altezza', '代码块完整高度显示', '코드 블록 전체 높이 표시'],
		'Exclude reasoning from context': ['思考内容をコンテキストから除外', 'Exclure le raisonnement du contexte', 'Excluir el razonamiento del contexto', 'Excluir raciocínio do contexto', 'Denkprozess aus dem Kontext ausschließen', 'Escludi il ragionamento dal contesto', '从上下文中排除推理内容', '추론 내용을 컨텍스트에서 제외'],
		'Disable reasoning content parsing': ['思考内容の解析を無効化', "Désactiver l'analyse du raisonnement", 'Desactivar el análisis del razonamiento', 'Desativar análise do raciocínio', 'Analyse des Denkprozesses deaktivieren', "Disattiva l'analisi del ragionamento", '禁用推理内容解析', '추론 내용 파싱 끄기'],
		'Show raw model names': ['モデル名をそのまま表示', 'Afficher les noms bruts des modèles', 'Mostrar nombres de modelo sin procesar', 'Mostrar nomes brutos dos modelos', 'Rohe Modellnamen anzeigen', 'Mostra i nomi grezzi dei modelli', '显示原始模型名称', '원본 모델 이름 표시'],
		'Show microphone on empty input': ['入力が空のときマイクを表示', 'Afficher le micro si la saisie est vide', 'Mostrar micrófono con la entrada vacía', 'Mostrar microfone com entrada vazia', 'Mikrofon bei leerer Eingabe anzeigen', 'Mostra il microfono con input vuoto', '输入为空时显示麦克风', '입력이 비어 있을 때 마이크 표시'],
		'Always show sidebar on desktop': ['デスクトップでは常にサイドバーを表示', 'Toujours afficher la barre latérale sur ordinateur', 'Mostrar siempre la barra lateral en escritorio', 'Sempre mostrar a barra lateral no desktop', 'Seitenleiste auf dem Desktop immer anzeigen', 'Mostra sempre la barra laterale su desktop', '桌面端始终显示侧边栏', '데스크톱에서 항상 사이드바 표시'],
		'Auto-show sidebar on new chat': ['新しいチャットでサイドバーを自動表示', 'Afficher la barre latérale pour une nouvelle discussion', 'Mostrar barra lateral en un chat nuevo', 'Mostrar barra lateral em novo chat', 'Seitenleiste bei neuem Chat anzeigen', 'Mostra la barra laterale nelle nuove chat', '新聊天时自动显示侧边栏', '새 채팅에서 사이드바 자동 표시'],
		'Ask for confirmation before changing conversation title': ['会話タイトルを変更する前に確認する', 'Demander confirmation avant de changer le titre', 'Confirmar antes de cambiar el título', 'Confirmar antes de alterar o título', 'Vor dem Ändern des Titels nachfragen', 'Chiedi conferma prima di cambiare il titolo', '更改对话标题前确认', '대화 제목 변경 전 확인'],
		'Paste long text to file length': ['長いテキストをファイル化する文字数', 'Longueur à partir de laquelle le texte collé devient un fichier', 'Longitud para pegar texto largo como archivo', 'Tamanho para colar texto longo como arquivo', 'Länge, ab der eingefügter Text zur Datei wird', 'Lunghezza oltre cui il testo incollato diventa file', '长文本转为文件的长度', '긴 텍스트를 파일로 붙여넣는 길이'],
		'Parse PDF as image': ['PDF を画像として解析', 'Analyser le PDF comme image', 'Analizar PDF como imagen', 'Analisar PDF como imagem', 'PDF als Bild verarbeiten', 'Analizza PDF come immagine', '将 PDF 作为图像解析', 'PDF를 이미지로 분석'],
		'Enable PDF as Images': ['PDF を画像として扱う', 'Activer les PDF en images', 'Activar PDF como imágenes', 'Ativar PDF como imagens', 'PDF als Bilder aktivieren', 'Abilita PDF come immagini', '启用 PDF 转图像', 'PDF를 이미지로 사용'],
		'Copy text attachments as plain text': ['テキスト添付をプレーンテキストでコピー', 'Copier les pièces jointes texte en texte brut', 'Copiar adjuntos de texto como texto plano', 'Copiar anexos de texto como texto simples', 'Textanhänge als reinen Text kopieren', 'Copia gli allegati di testo come testo semplice', '将文本附件复制为纯文本', '텍스트 첨부를 일반 텍스트로 복사'],
		"Settings are saved in browser's localStorage": ['設定はブラウザの localStorage に保存されます', 'Les paramètres sont enregistrés dans le localStorage du navigateur', 'La configuración se guarda en el localStorage del navegador', 'As configurações são salvas no localStorage do navegador', 'Einstellungen werden im localStorage des Browsers gespeichert', 'Le impostazioni sono salvate nel localStorage del browser', '设置保存在浏览器的 localStorage 中', '설정은 브라우저의 localStorage에 저장됩니다'],
		'Update Conversation Title?': ['会話タイトルを更新しますか？', 'Mettre à jour le titre ?', '¿Actualizar el título de la conversación?', 'Atualizar o título da conversa?', 'Titel der Unterhaltung aktualisieren?', 'Aggiornare il titolo della conversazione?', '更新对话标题？', '대화 제목을 업데이트할까요?'],
		'Update Title': ['タイトルを更新', 'Mettre à jour le titre', 'Actualizar título', 'Atualizar título', 'Titel aktualisieren', 'Aggiorna titolo', '更新标题', '제목 업데이트'],
		'Keep Current Title': ['現在のタイトルのまま', 'Conserver le titre actuel', 'Mantener el título actual', 'Manter o título atual', 'Aktuellen Titel behalten', 'Mantieni il titolo attuale', '保留当前标题', '현재 제목 유지'],
		'Current title:': ['現在のタイトル:', 'Titre actuel :', 'Título actual:', 'Título atual:', 'Aktueller Titel:', 'Titolo attuale:', '当前标题：', '현재 제목:'],
		'New title would be:': ['新しいタイトル:', 'Nouveau titre :', 'Nuevo título:', 'Novo título:', 'Neuer Titel:', 'Nuovo titolo:', '新标题：', '새 제목:'],
		'Title': ['タイトル', 'Titre', 'Título', 'Título', 'Titel', 'Titolo', '标题', '제목'],
		'Add': ['追加', 'Ajouter', 'Añadir', 'Adicionar', 'Hinzufügen', 'Aggiungi', '添加', '추가'],
		'Show full system message': ['システムメッセージ全体を表示', 'Afficher le message système complet', 'Mostrar el mensaje del sistema completo', 'Mostrar a mensagem do sistema completa', 'Vollständige Systemnachricht anzeigen', 'Mostra il messaggio di sistema completo', '显示完整的系统消息', '전체 시스템 메시지 표시'],
		'Show system message in conversations': ['会話内にシステムメッセージを表示', 'Afficher le message système dans les conversations', 'Mostrar mensaje del sistema en las conversaciones', 'Mostrar mensagem do sistema nas conversas', 'Systemnachricht in Unterhaltungen anzeigen', 'Mostra il messaggio di sistema nelle conversazioni', '在对话中显示系统消息', '대화에 시스템 메시지 표시'],
		'Collapse System Message': ['システムメッセージを折りたたむ', 'Réduire le message système', 'Contraer el mensaje del sistema', 'Recolher mensagem do sistema', 'Systemnachricht einklappen', 'Comprimi messaggio di sistema', '折叠系统消息', '시스템 메시지 접기'],
		'Messages': ['メッセージ', 'Messages', 'Mensajes', 'Mensagens', 'Nachrichten', 'Messaggi', '消息', '메시지'],
		'Generation': ['生成', 'Génération', 'Generación', 'Geração', 'Generierung', 'Generazione', '生成', '생성'],
		'Reading': ['読み込み', 'Lecture', 'Lectura', 'Leitura', 'Lesen', 'Lettura', '读取', '읽기'],
		'Reading (prompt processing)': ['読み込み（プロンプト処理）', 'Lecture (traitement du prompt)', 'Lectura (procesamiento del prompt)', 'Leitura (processamento do prompt)', 'Lesen (Prompt-Verarbeitung)', 'Lettura (elaborazione del prompt)', '读取（提示词处理）', '읽기 (프롬프트 처리)'],
		'Summary': ['概要', 'Résumé', 'Resumen', 'Resumo', 'Zusammenfassung', 'Riepilogo', '摘要', '요약'],
		'Tools': ['ツール', 'Outils', 'Herramientas', 'Ferramentas', 'Werkzeuge', 'Strumenti', '工具', '도구'],
		'Tool calls': ['ツール呼び出し', "Appels d'outils", 'Llamadas a herramientas', 'Chamadas de ferramentas', 'Tool-Aufrufe', 'Chiamate agli strumenti', '工具调用', '도구 호출'],
		'Templates': ['テンプレート', 'Modèles', 'Plantillas', 'Modelos', 'Vorlagen', 'Modelli', '模板', '템플릿'],
		'Waiting for result...': ['結果を待っています...', 'En attente du résultat...', 'Esperando el resultado...', 'Aguardando o resultado...', 'Warte auf Ergebnis...', 'In attesa del risultato...', '正在等待结果...', '결과를 기다리는 중...'],
		'Arguments:': ['引数:', 'Arguments :', 'Argumentos:', 'Argumentos:', 'Argumente:', 'Argomenti:', '参数：', '인수:'],
		'Result:': ['結果:', 'Résultat :', 'Resultado:', 'Resultado:', 'Ergebnis:', 'Risultato:', '结果：', '결과:'],
		'Success!': ['成功しました！', 'Succès !', '¡Éxito!', 'Sucesso!', 'Erfolgreich!', 'Fatto!', '成功！', '성공!'],
		'Press': ['', 'Appuyez sur', 'Pulsa', 'Pressione', 'Drücken Sie', 'Premi', '按', ''],
		'to send,': ['で送信、', 'pour envoyer,', 'para enviar,', 'para enviar,', 'zum Senden,', 'per inviare,', '发送，', '(으)로 보내기,'],
		'for new line': ['で改行', 'pour un saut de ligne', 'para nueva línea', 'para nova linha', 'für neue Zeile', 'per andare a capo', '换行', '(으)로 줄바꿈'],
		'Message copied to clipboard': ['メッセージをコピーしました', 'Message copié', 'Mensaje copiado', 'Mensagem copiada', 'Nachricht kopiert', 'Messaggio copiato', '消息已复制', '메시지를 복사했습니다'],
		'Copied to clipboard': ['クリップボードにコピーしました', 'Copié dans le presse-papiers', 'Copiado al portapapeles', 'Copiado para a área de transferência', 'In die Zwischenablage kopiert', 'Copiato negli appunti', '已复制到剪贴板', '클립보드에 복사했습니다'],
		'Code copied to clipboard': ['コードをコピーしました', 'Code copié', 'Código copiado', 'Código copiado', 'Code kopiert', 'Codice copiato', '代码已复制', '코드를 복사했습니다'],
		'MCP Servers': ['MCP サーバー', 'Serveurs MCP', 'Servidores MCP', 'Servidores MCP', 'MCP-Server', 'Server MCP', 'MCP 服务器', 'MCP 서버'],
		'Manage MCP Servers': ['MCP サーバーを管理', 'Gérer les serveurs MCP', 'Administrar servidores MCP', 'Gerenciar servidores MCP', 'MCP-Server verwalten', 'Gestisci server MCP', '管理 MCP 服务器', 'MCP 서버 관리'],
		'Manage Servers': ['サーバーを管理', 'Gérer les serveurs', 'Administrar servidores', 'Gerenciar servidores', 'Server verwalten', 'Gestisci server', '管理服务器', '서버 관리'],
		'Add New Server': ['サーバーを追加', 'Ajouter un serveur', 'Añadir servidor', 'Adicionar servidor', 'Neuen Server hinzufügen', 'Aggiungi server', '添加服务器', '새 서버 추가'],
		'Server URL': ['サーバー URL', 'URL du serveur', 'URL del servidor', 'URL do servidor', 'Server-URL', 'URL del server', '服务器 URL', '서버 URL'],
		'Delete Server': ['サーバーを削除', 'Supprimer le serveur', 'Eliminar servidor', 'Excluir servidor', 'Server löschen', 'Elimina server', '删除服务器', '서버 삭제'],
		'(optional)': ['（任意）', '(facultatif)', '(opcional)', '(opcional)', '(optional)', '(facoltativo)', '（可选）', '(선택 사항)'],
		'View as Text': ['テキストとして表示', 'Afficher en texte', 'Ver como texto', 'Ver como texto', 'Als Text anzeigen', 'Visualizza come testo', '以文本查看', '텍스트로 보기'],
		'View and manage all attached files': ['添付ファイルの表示と管理', 'Voir et gérer les pièces jointes', 'Ver y administrar los adjuntos', 'Ver e gerenciar anexos', 'Alle Anhänge anzeigen und verwalten', 'Visualizza e gestisci gli allegati', '查看和管理所有附件', '첨부 파일 보기 및 관리'],
		'Preview not available for this file type': ['このファイル形式はプレビューできません', "Aperçu indisponible pour ce type de fichier", 'Vista previa no disponible para este tipo', 'Pré-visualização indisponível para este tipo', 'Keine Vorschau für diesen Dateityp', 'Anteprima non disponibile per questo tipo di file', '此文件类型无法预览', '이 파일 형식은 미리 볼 수 없습니다'],
		'No content available': ['内容がありません', 'Aucun contenu', 'No hay contenido', 'Nenhum conteúdo', 'Kein Inhalt verfügbar', 'Nessun contenuto', '没有内容', '내용이 없습니다'],
		'Image cannot be displayed': ['画像を表示できません', "Impossible d'afficher l'image", 'No se puede mostrar la imagen', 'Não é possível exibir a imagem', 'Bild kann nicht angezeigt werden', "Impossibile visualizzare l'immagine", '无法显示图片', '이미지를 표시할 수 없습니다'],
		// ---- i18n review 2026-11-24
		"Converting PDF to images...": ["PDF を画像に変換中...", "Conversion du PDF en images...", "Convirtiendo el PDF en imágenes...", "Convertendo PDF em imagens...", "PDF wird in Bilder umgewandelt...", "Conversione del PDF in immagini...", "正在将 PDF 转换为图片...", "PDF를 이미지로 변환 중..."],
		"Failed to load PDF images": ["PDF の画像を読み込めませんでした", "Impossible de charger les images du PDF", "No se pudieron cargar las imágenes del PDF", "Falha ao carregar as imagens do PDF", "PDF-Bilder konnten nicht geladen werden", "Impossibile caricare le immagini del PDF", "无法加载 PDF 图片", "PDF 이미지를 불러오지 못했습니다"],
		"No PDF pages available": ["PDF のページがありません", "Aucune page PDF", "No hay páginas PDF", "Nenhuma página de PDF", "Keine PDF-Seiten verfügbar", "Nessuna pagina PDF", "没有可用的 PDF 页面", "PDF 페이지가 없습니다"],
		"Your browser does not support the audio element.": ["お使いのブラウザは音声再生に対応していません。", "Votre navigateur ne prend pas en charge l'audio.", "Tu navegador no admite el elemento de audio.", "Seu navegador não suporta o elemento de áudio.", "Ihr Browser unterstützt kein Audio-Element.", "Il browser non supporta l'elemento audio.", "你的浏览器不支持音频元素。", "브라우저가 오디오 요소를 지원하지 않습니다."],
		"Audio preview not available": ["音声をプレビューできません", "Aperçu audio indisponible", "Vista previa de audio no disponible", "Pré-visualização de áudio indisponível", "Audiovorschau nicht verfügbar", "Anteprima audio non disponibile", "无法预览音频", "오디오 미리보기를 사용할 수 없습니다"],
		"Context size:": ["コンテキストサイズ:", "Taille du contexte :", "Tamaño del contexto:", "Tamanho do contexto:", "Kontextgröße:", "Dimensione del contesto:", "上下文大小：", "컨텍스트 크기:"],
		"Prompt tokens:": ["プロンプトトークン数:", "Jetons du prompt :", "Tokens del prompt:", "Tokens do prompt:", "Prompt-Tokens:", "Token del prompt:", "提示词令牌数：", "프롬프트 토큰:"],
		"Requested:": ["要求:", "Demandé :", "Solicitado:", "Solicitado:", "Angefordert:", "Richiesto:", "请求：", "요청:"],
		"Empty Files:": ["空のファイル:", "Fichiers vides :", "Archivos vacíos:", "Arquivos vazios:", "Leere Dateien:", "File vuoti:", "空文件：", "빈 파일:"],
		"What happened:": ["何が起きたか:", "Ce qui s'est passé :", "Qué ocurrió:", "O que aconteceu:", "Was ist passiert:", "Cosa è successo:", "发生了什么：", "발생한 일:"],
		"Empty files cannot be processed or sent to the AI model": ["空のファイルは処理も AI モデルへの送信もできません", "Les fichiers vides ne peuvent pas être traités ni envoyés au modèle", "Los archivos vacíos no se pueden procesar ni enviar al modelo", "Arquivos vazios não podem ser processados nem enviados ao modelo", "Leere Dateien können nicht verarbeitet oder an das Modell gesendet werden", "I file vuoti non possono essere elaborati né inviati al modello", "空文件无法处理或发送给 AI 模型", "빈 파일은 처리하거나 AI 모델에 보낼 수 없습니다"],
		"These files have been automatically removed from your attachments": ["これらのファイルは添付から自動的に削除されました", "Ces fichiers ont été retirés automatiquement des pièces jointes", "Estos archivos se quitaron automáticamente de los adjuntos", "Esses arquivos foram removidos automaticamente dos anexos", "Diese Dateien wurden automatisch aus den Anhängen entfernt", "Questi file sono stati rimossi automaticamente dagli allegati", "这些文件已被自动从附件中移除", "이 파일들은 첨부에서 자동으로 제거되었습니다"],
		"You can try uploading files with content instead": ["中身のあるファイルをアップロードしてください", "Essayez plutôt d'envoyer des fichiers non vides", "Intenta subir archivos con contenido", "Tente enviar arquivos com conteúdo", "Laden Sie stattdessen Dateien mit Inhalt hoch", "Prova a caricare file con contenuto", "请改为上传有内容的文件", "내용이 있는 파일을 업로드해 보세요"],
		"The following files are empty and have been removed from your attachments:": ["次のファイルは空のため添付から削除されました:", "Les fichiers suivants sont vides et ont été retirés des pièces jointes :", "Los siguientes archivos están vacíos y se quitaron de los adjuntos:", "Os seguintes arquivos estão vazios e foram removidos dos anexos:", "Folgende Dateien sind leer und wurden aus den Anhängen entfernt:", "I seguenti file sono vuoti e sono stati rimossi dagli allegati:", "以下文件为空，已从附件中移除：", "다음 파일은 비어 있어 첨부에서 제거되었습니다:"],
		"Audio files processing requires an audio model": ["音声ファイルの処理には音声対応モデルが必要です", "Le traitement audio nécessite un modèle audio", "Procesar audio requiere un modelo de audio", "O processamento de áudio requer um modelo de áudio", "Audioverarbeitung erfordert ein Audio-Modell", "L'elaborazione audio richiede un modello audio", "处理音频文件需要音频模型", "오디오 파일 처리에는 오디오 모델이 필요합니다"],
		"PDFs will be converted to text. Image-based PDFs may not work properly.": ["PDF はテキストに変換されます。画像主体の PDF は正しく処理できない場合があります。", "Les PDF seront convertis en texte. Les PDF à base d'images peuvent mal fonctionner.", "Los PDF se convertirán a texto. Los PDF basados en imágenes pueden no funcionar bien.", "Os PDFs serão convertidos em texto. PDFs baseados em imagem podem não funcionar bem.", "PDFs werden in Text umgewandelt. Bildbasierte PDFs funktionieren evtl. nicht richtig.", "I PDF verranno convertiti in testo. I PDF basati su immagini potrebbero non funzionare.", "PDF 将被转换为文本。以图片为主的 PDF 可能无法正确处理。", "PDF는 텍스트로 변환됩니다. 이미지 기반 PDF는 제대로 처리되지 않을 수 있습니다."],
		"Unsupported File Types": ["対応していないファイル形式", "Types de fichiers non pris en charge", "Tipos de archivo no compatibles", "Tipos de arquivo não suportados", "Nicht unterstützte Dateitypen", "Tipi di file non supportati", "不支持的文件类型", "지원되지 않는 파일 형식"],
		"This model supports:": ["このモデルが対応する形式:", "Ce modèle prend en charge :", "Este modelo admite:", "Este modelo suporta:", "Dieses Modell unterstützt:", "Questo modello supporta:", "此模型支持：", "이 모델이 지원하는 형식:"],
		"The selected model does not support vision. Only the extracted": ["選択中のモデルは画像に対応していません。抽出された", "Le modèle choisi ne gère pas la vision. Seul le", "El modelo seleccionado no admite visión. Solo el", "O modelo selecionado não suporta visão. Somente o", "Das gewählte Modell unterstützt keine Bilder. Nur der extrahierte", "Il modello selezionato non supporta la visione. Solo il", "所选模型不支持视觉。只有提取出的", "선택한 모델은 비전을 지원하지 않습니다. 추출된"],
		"will be sent to the model.": ["のみがモデルに送信されます。", "extrait sera envoyé au modèle.", "extraído se enviará al modelo.", "extraído será enviado ao modelo.", "wird an das Modell gesendet.", "estratto verrà inviato al modello.", "会发送给模型。", "만 모델에 전송됩니다."],
		"Toggle content": ["内容の表示切替", "Afficher/masquer le contenu", "Mostrar/ocultar contenido", "Alternar conteúdo", "Inhalt ein-/ausblenden", "Mostra/nascondi contenuto", "切换内容显示", "내용 표시 전환"],
		"Receiving arguments...": ["引数を受信中...", "Réception des arguments...", "Recibiendo argumentos...", "Recebendo argumentos...", "Argumente werden empfangen...", "Ricezione degli argomenti...", "正在接收参数...", "인수 수신 중..."],
		"Agentic summary": ["エージェント実行の概要", "Résumé agentique", "Resumen del agente", "Resumo do agente", "Agenten-Zusammenfassung", "Riepilogo agentico", "智能体运行摘要", "에이전트 실행 요약"],
		"Preview only": ["プレビューのみ", "Aperçu uniquement", "Solo vista previa", "Somente pré-visualização", "Nur Vorschau", "Solo anteprima", "仅预览", "미리보기만"],
		"Do you want to update the conversation title to match the first message content?": ["会話タイトルを最初のメッセージの内容に合わせて更新しますか？", "Mettre à jour le titre de la conversation d'après le premier message ?", "¿Actualizar el título de la conversación según el primer mensaje?", "Atualizar o título da conversa de acordo com a primeira mensagem?", "Titel der Unterhaltung an die erste Nachricht anpassen?", "Aggiornare il titolo della conversazione in base al primo messaggio?", "要根据第一条消息更新对话标题吗？", "첫 메시지 내용에 맞게 대화 제목을 업데이트할까요?"],
		"The requested model could not be found. Select an available model to continue.": ["要求されたモデルが見つかりません。利用可能なモデルを選択してください。", "Le modèle demandé est introuvable. Choisissez un modèle disponible.", "No se encontró el modelo solicitado. Selecciona un modelo disponible.", "O modelo solicitado não foi encontrado. Selecione um modelo disponível.", "Das angeforderte Modell wurde nicht gefunden. Wählen Sie ein verfügbares Modell.", "Modello richiesto non trovato. Seleziona un modello disponibile.", "找不到请求的模型。请选择可用的模型以继续。", "요청한 모델을 찾을 수 없습니다. 사용 가능한 모델을 선택하세요."],
		"File Path": ["ファイルパス", "Chemin du fichier", "Ruta del archivo", "Caminho do arquivo", "Dateipfad", "Percorso del file", "文件路径", "파일 경로"],
		"Training Context": ["学習時のコンテキスト長", "Contexte d'entraînement", "Contexto de entrenamiento", "Contexto de treinamento", "Trainingskontext", "Contesto di addestramento", "训练上下文长度", "학습 컨텍스트"],
		"Embedding Size": ["埋め込み次元", "Taille d'embedding", "Tamaño de embedding", "Tamanho do embedding", "Embedding-Größe", "Dimensione embedding", "嵌入维度", "임베딩 크기"],
		"Vocabulary Size": ["語彙サイズ", "Taille du vocabulaire", "Tamaño del vocabulario", "Tamanho do vocabulário", "Vokabulargröße", "Dimensione del vocabolario", "词表大小", "어휘 크기"],
		"Vocabulary Type": ["語彙の種類", "Type de vocabulaire", "Tipo de vocabulario", "Tipo de vocabulário", "Vokabulartyp", "Tipo di vocabolario", "词表类型", "어휘 유형"],
		"Parallel Slots": ["並列スロット数", "Emplacements parallèles", "Ranuras paralelas", "Slots paralelos", "Parallele Slots", "Slot paralleli", "并行槽位", "병렬 슬롯"],
		"Build Info": ["ビルド情報", "Infos de build", "Información de compilación", "Informações da build", "Build-Infos", "Informazioni build", "构建信息", "빌드 정보"],
		"Internal Error": ["内部エラー", "Erreur interne", "Error interno", "Erro interno", "Interner Fehler", "Errore interno", "内部错误", "내부 오류"],
		"Failed to unload model": ["モデルを解放できませんでした", "Échec du déchargement du modèle", "No se pudo descargar el modelo", "Falha ao descarregar o modelo", "Modell konnte nicht entladen werden", "Impossibile scaricare il modello", "卸载模型失败", "모델을 언로드하지 못했습니다"],
		"Unknown error executing prompt": ["プロンプトの実行中に不明なエラーが発生しました", "Erreur inconnue lors de l'exécution du prompt", "Error desconocido al ejecutar el prompt", "Erro desconhecido ao executar o prompt", "Unbekannter Fehler beim Ausführen des Prompts", "Errore sconosciuto durante l'esecuzione del prompt", "执行提示词时发生未知错误", "프롬프트 실행 중 알 수 없는 오류"],
		"Create a new conversation branching from this message.": ["このメッセージから分岐した新しい会話を作成します。", "Créer une nouvelle conversation à partir de ce message.", "Crear una nueva conversación a partir de este mensaje.", "Criar uma nova conversa a partir desta mensagem.", "Neue Unterhaltung ab dieser Nachricht erstellen.", "Crea una nuova conversazione a partire da questo messaggio.", "从此消息创建新的对话分支。", "이 메시지에서 분기한 새 대화를 만듭니다."],
		"Also delete all forked conversations": ["分岐した会話もすべて削除する", "Supprimer aussi les conversations bifurquées", "Eliminar también las conversaciones bifurcadas", "Excluir também as conversas ramificadas", "Auch alle abgezweigten Unterhaltungen löschen", "Elimina anche le conversazioni diramate", "同时删除所有分支对话", "분기된 대화도 모두 삭제"],
		"Displays the mobile sidebar.": ["モバイル用サイドバーを表示します。", "Affiche la barre latérale mobile.", "Muestra la barra lateral móvil.", "Exibe a barra lateral móvel.", "Zeigt die mobile Seitenleiste an.", "Mostra la barra laterale mobile.", "显示移动端侧边栏。", "모바일 사이드바를 표시합니다."],
		"Save & Retry": ["保存して再試行", "Enregistrer et réessayer", "Guardar y reintentar", "Salvar e tentar novamente", "Speichern & erneut versuchen", "Salva e riprova", "保存并重试", "저장 후 다시 시도"],
		"Include all attachments": ["すべての添付を含める", "Inclure toutes les pièces jointes", "Incluir todos los adjuntos", "Incluir todos os anexos", "Alle Anhänge einschließen", "Includi tutti gli allegati", "包含所有附件", "모든 첨부 포함"],
		"Edit system message...": ["システムメッセージを編集...", "Modifier le message système...", "Editar mensaje del sistema...", "Editar mensagem do sistema...", "Systemnachricht bearbeiten...", "Modifica messaggio di sistema...", "编辑系统消息...", "시스템 메시지 편집..."],
		"Edit assistant message...": ["アシスタントのメッセージを編集...", "Modifier le message de l'assistant...", "Editar mensaje del asistente...", "Editar mensagem do assistente...", "Assistentennachricht bearbeiten...", "Modifica messaggio dell'assistente...", "编辑助手消息...", "어시스턴트 메시지 편집..."],
		"Enter your API key...": ["API キーを入力...", "Saisissez votre clé API...", "Introduce tu clave API...", "Digite sua chave de API...", "API-Schlüssel eingeben...", "Inserisci la chiave API...", "输入 API 密钥...", "API 키 입력..."],
		"Generated tokens": ["生成トークン数", "Jetons générés", "Tokens generados", "Tokens gerados", "Generierte Tokens", "Token generati", "生成的令牌数", "생성된 토큰"],
		"Generation time": ["生成時間", "Temps de génération", "Tiempo de generación", "Tempo de geração", "Generierungszeit", "Tempo di generazione", "生成时间", "생성 시간"],
		"Generation speed": ["生成速度", "Vitesse de génération", "Velocidad de generación", "Velocidade de geração", "Generierungsgeschwindigkeit", "Velocità di generazione", "生成速度", "생성 속도"],
		"Prompt tokens": ["プロンプトトークン数", "Jetons du prompt", "Tokens del prompt", "Tokens do prompt", "Prompt-Tokens", "Token del prompt", "提示词令牌数", "프롬프트 토큰"],
		"Prompt processing time": ["プロンプト処理時間", "Temps de traitement du prompt", "Tiempo de procesamiento del prompt", "Tempo de processamento do prompt", "Prompt-Verarbeitungszeit", "Tempo di elaborazione del prompt", "提示词处理时间", "프롬프트 처리 시간"],
		"Prompt processing speed": ["プロンプト処理速度", "Vitesse de traitement du prompt", "Velocidad de procesamiento del prompt", "Velocidade de processamento do prompt", "Prompt-Verarbeitungsgeschwindigkeit", "Velocità di elaborazione del prompt", "提示词处理速度", "프롬프트 처리 속도"],
		"Tool calls executed": ["実行したツール呼び出し数", "Appels d'outils exécutés", "Llamadas a herramientas ejecutadas", "Chamadas de ferramentas executadas", "Ausgeführte Tool-Aufrufe", "Chiamate agli strumenti eseguite", "已执行的工具调用", "실행된 도구 호출"],
		"Tool execution time": ["ツール実行時間", "Temps d'exécution des outils", "Tiempo de ejecución de herramientas", "Tempo de execução das ferramentas", "Tool-Ausführungszeit", "Tempo di esecuzione degli strumenti", "工具执行时间", "도구 실행 시간"],
		"Tool execution rate": ["ツール実行レート", "Débit d'exécution des outils", "Tasa de ejecución de herramientas", "Taxa de execução das ferramentas", "Tool-Ausführungsrate", "Frequenza di esecuzione degli strumenti", "工具执行速率", "도구 실행 속도"],
		"Agentic turns (LLM calls)": ["エージェントのターン数（LLM 呼び出し）", "Tours agentiques (appels LLM)", "Turnos del agente (llamadas al LLM)", "Turnos do agente (chamadas ao LLM)", "Agenten-Runden (LLM-Aufrufe)", "Turni agentici (chiamate LLM)", "智能体轮次（LLM 调用）", "에이전트 턴 (LLM 호출)"],
		"Total tokens generated": ["総生成トークン数", "Total de jetons générés", "Total de tokens generados", "Total de tokens gerados", "Insgesamt generierte Tokens", "Token generati in totale", "生成的令牌总数", "생성된 총 토큰"],
		"Total time (LLM + tools)": ["合計時間（LLM + ツール）", "Temps total (LLM + outils)", "Tiempo total (LLM + herramientas)", "Tempo total (LLM + ferramentas)", "Gesamtzeit (LLM + Tools)", "Tempo totale (LLM + strumenti)", "总时间（LLM + 工具）", "총 시간 (LLM + 도구)"],
		"Backend sampling": ["バックエンドでのサンプリング", "Échantillonnage côté backend", "Muestreo en el backend", "Amostragem no backend", "Backend-Sampling", "Campionamento sul backend", "后端采样", "백엔드 샘플링"],
		"Agentic loop max turns": ["エージェントループの最大ターン数", "Tours max. de la boucle agentique", "Máximo de turnos del bucle del agente", "Máximo de turnos do loop do agente", "Max. Runden der Agentenschleife", "Turni massimi del ciclo agentico", "智能体循环最大轮次", "에이전트 루프 최대 턴"],
		"Always show agentic turns in conversation": ["会話内にエージェントのターンを常に表示", "Toujours afficher les tours agentiques", "Mostrar siempre los turnos del agente", "Sempre mostrar os turnos do agente", "Agenten-Runden immer anzeigen", "Mostra sempre i turni agentici", "始终在对话中显示智能体轮次", "대화에 에이전트 턴 항상 표시"],
		"Max lines per tool preview": ["ツールプレビューの最大行数", "Lignes max. par aperçu d'outil", "Máximo de líneas por vista previa de herramienta", "Máximo de linhas por pré-visualização", "Max. Zeilen pro Tool-Vorschau", "Righe massime per anteprima strumento", "每个工具预览的最大行数", "도구 미리보기 최대 줄 수"],
		"Enable raw output toggle": ["生の出力の切替ボタンを表示", "Activer le bouton de sortie brute", "Activar el botón de salida sin procesar", "Ativar botão de saída bruta", "Umschalter für Rohausgabe aktivieren", "Abilita il pulsante output grezzo", "启用原始输出切换", "원본 출력 전환 사용"],
		"Custom JSON": ["カスタム JSON", "JSON personnalisé", "JSON personalizado", "JSON personalizado", "Benutzerdefiniertes JSON", "JSON personalizzato", "自定义 JSON", "사용자 지정 JSON"],
		"All conversations deleted": ["すべての会話を削除しました", "Toutes les conversations ont été supprimées", "Se eliminaron todas las conversaciones", "Todas as conversas foram excluídas", "Alle Unterhaltungen gelöscht", "Tutte le conversazioni eliminate", "已删除所有对话", "모든 대화를 삭제했습니다"],
		"Failed to delete conversations": ["会話を削除できませんでした", "Échec de la suppression des conversations", "No se pudieron eliminar las conversaciones", "Falha ao excluir as conversas", "Unterhaltungen konnten nicht gelöscht werden", "Impossibile eliminare le conversazioni", "删除对话失败", "대화를 삭제하지 못했습니다"],
		"Conversation forked": ["会話を分岐しました", "Conversation bifurquée", "Conversación bifurcada", "Conversa ramificada", "Unterhaltung abgezweigt", "Conversazione diramata", "已创建对话分支", "대화를 분기했습니다"],
		"Failed to fork conversation": ["会話を分岐できませんでした", "Échec de la bifurcation", "No se pudo bifurcar la conversación", "Falha ao ramificar a conversa", "Abzweigen fehlgeschlagen", "Impossibile diramare la conversazione", "创建对话分支失败", "대화를 분기하지 못했습니다"],
		"Import failed": ["インポートに失敗しました", "Échec de l'importation", "Error al importar", "Falha na importação", "Import fehlgeschlagen", "Importazione non riuscita", "导入失败", "가져오기에 실패했습니다"],
		"Resource already attached": ["このリソースは添付済みです", "Ressource déjà jointe", "El recurso ya está adjunto", "Recurso já anexado", "Ressource bereits angehängt", "Risorsa già allegata", "资源已附加", "이미 첨부된 리소스입니다"],
		"No conversations to export": ["エクスポートする会話がありません", "Aucune conversation à exporter", "No hay conversaciones para exportar", "Nenhuma conversa para exportar", "Keine Unterhaltungen zum Exportieren", "Nessuna conversazione da esportare", "没有可导出的对话", "내보낼 대화가 없습니다"],
		"No conversations to delete": ["削除する会話がありません", "Aucune conversation à supprimer", "No hay conversaciones para eliminar", "Nenhuma conversa para excluir", "Keine Unterhaltungen zum Löschen", "Nessuna conversazione da eliminare", "没有可删除的对话", "삭제할 대화가 없습니다"],
		"Failed to load conversations": ["会話を読み込めませんでした", "Échec du chargement des conversations", "No se pudieron cargar las conversaciones", "Falha ao carregar as conversas", "Unterhaltungen konnten nicht geladen werden", "Impossibile caricare le conversazioni", "加载对话失败", "대화를 불러오지 못했습니다"],
		"Error processing file": ["ファイルの処理中にエラーが発生しました", "Erreur lors du traitement du fichier", "Error al procesar el archivo", "Erro ao processar o arquivo", "Fehler beim Verarbeiten der Datei", "Errore durante l'elaborazione del file", "处理文件时出错", "파일 처리 중 오류"],
		"PDF parsing as images enabled!": ["PDF を画像として解析する設定を有効にしました！", "Analyse des PDF en images activée !", "¡Análisis de PDF como imágenes activado!", "Análise de PDF como imagens ativada!", "PDF-Analyse als Bilder aktiviert!", "Analisi dei PDF come immagini attivata!", "已启用将 PDF 作为图像解析！", "PDF 이미지 분석을 켰습니다!"],
		"You can enable parsing PDF as images with vision models.": ["Vision 対応モデルでは PDF を画像として解析できます。", "Avec un modèle de vision, vous pouvez analyser les PDF en images.", "Con modelos de visión puedes analizar PDF como imágenes.", "Com modelos de visão você pode analisar PDFs como imagens.", "Mit Vision-Modellen können PDFs als Bilder verarbeitet werden.", "Con i modelli di visione puoi analizzare i PDF come immagini.", "使用视觉模型时可将 PDF 作为图像解析。", "비전 모델에서는 PDF를 이미지로 분석할 수 있습니다."],
		"PDF setting changed: Non-vision model detected, PDFs will be processed as text instead of images.": ["PDF の設定を変更しました: 画像非対応のモデルのため、PDF は画像ではなくテキストとして処理します。", "Paramètre PDF modifié : modèle sans vision, les PDF seront traités comme du texte.", "Configuración de PDF cambiada: el modelo no admite visión; los PDF se procesarán como texto.", "Configuração de PDF alterada: modelo sem visão; os PDFs serão processados como texto.", "PDF-Einstellung geändert: Modell ohne Vision, PDFs werden als Text verarbeitet.", "Impostazione PDF modificata: modello senza visione, i PDF saranno elaborati come testo.", "PDF 设置已更改：检测到非视觉模型，PDF 将作为文本处理。", "PDF 설정 변경: 비전 미지원 모델이므로 PDF를 텍스트로 처리합니다."],
		"Invalid data: missing conversation": ["無効なデータ: 会話がありません", "Données invalides : conversation manquante", "Datos no válidos: falta la conversación", "Dados inválidos: conversa ausente", "Ungültige Daten: Unterhaltung fehlt", "Dati non validi: conversazione mancante", "数据无效：缺少对话", "잘못된 데이터: 대화가 없습니다"],
		"Connecting...": ["接続中...", "Connexion...", "Conectando...", "Conectando...", "Verbinde...", "Connessione...", "正在连接...", "연결 중..."],
		"Initializing...": ["初期化中...", "Initialisation...", "Inicializando...", "Inicializando...", "Initialisierung...", "Inizializzazione...", "正在初始化...", "초기화 중..."],
		"Retrying...": ["再試行中...", "Nouvelle tentative...", "Reintentando...", "Tentando novamente...", "Neuer Versuch...", "Nuovo tentativo...", "正在重试...", "다시 시도 중..."],
		"Running...": ["実行中...", "Exécution...", "Ejecutando...", "Executando...", "Wird ausgeführt...", "In esecuzione...", "正在运行...", "실행 중..."],
		"MCP Resources": ["MCP リソース", "Ressources MCP", "Recursos MCP", "Recursos MCP", "MCP-Ressourcen", "Risorse MCP", "MCP 资源", "MCP 리소스"],
		"MCP Prompt": ["MCP プロンプト", "Prompt MCP", "Prompt MCP", "Prompt MCP", "MCP-Prompt", "Prompt MCP", "MCP 提示词", "MCP 프롬프트"],
		"Select a resource to preview": ["プレビューするリソースを選択", "Choisissez une ressource à prévisualiser", "Selecciona un recurso para previsualizar", "Selecione um recurso para pré-visualizar", "Ressource zur Vorschau auswählen", "Seleziona una risorsa da visualizzare", "选择要预览的资源", "미리 볼 리소스를 선택하세요"],
		"Servers provided by the app settings are shown here too and are read-only in WebUI.": ["アプリの設定で指定したサーバーもここに表示されます（WebUI では読み取り専用）。", "Les serveurs définis dans l'application apparaissent aussi ici, en lecture seule.", "Los servidores de la configuración de la app también aparecen aquí (solo lectura).", "Os servidores definidos no app também aparecem aqui (somente leitura).", "Server aus den App-Einstellungen werden hier ebenfalls angezeigt (schreibgeschützt).", "Anche i server delle impostazioni dell'app appaiono qui (sola lettura).", "应用设置中提供的服务器也会显示在这里（WebUI 中只读）。", "앱 설정의 서버도 여기에 표시됩니다 (WebUI에서는 읽기 전용)."],
		"No MCP Servers configured yet. Add one to enable agentic features.": ["MCP サーバーがまだ設定されていません。追加するとエージェント機能を使えます。", "Aucun serveur MCP configuré. Ajoutez-en un pour activer les fonctions agentiques.", "Aún no hay servidores MCP. Añade uno para activar las funciones de agente.", "Nenhum servidor MCP configurado. Adicione um para ativar os recursos de agente.", "Noch keine MCP-Server konfiguriert. Fügen Sie einen hinzu, um Agentenfunktionen zu nutzen.", "Nessun server MCP configurato. Aggiungine uno per attivare le funzioni agentiche.", "尚未配置 MCP 服务器。添加后即可使用智能体功能。", "아직 MCP 서버가 없습니다. 추가하면 에이전트 기능을 사용할 수 있습니다."],
		"Configure Server": ["サーバーを設定", "Configurer le serveur", "Configurar servidor", "Configurar servidor", "Server konfigurieren", "Configura server", "配置服务器", "서버 설정"],
		"Server instructions": ["サーバーの指示", "Instructions du serveur", "Instrucciones del servidor", "Instruções do servidor", "Server-Anweisungen", "Istruzioni del server", "服务器说明", "서버 지침"],
		"Available resources": ["利用可能なリソース", "Ressources disponibles", "Recursos disponibles", "Recursos disponíveis", "Verfügbare Ressourcen", "Risorse disponibili", "可用资源", "사용 가능한 리소스"],
		"No resources": ["リソースがありません", "Aucune ressource", "Sin recursos", "Nenhum recurso", "Keine Ressourcen", "Nessuna risorsa", "没有资源", "리소스 없음"],
		"Resolved URI:": ["解決された URI:", "URI résolue :", "URI resuelta:", "URI resolvida:", "Aufgelöste URI:", "URI risolto:", "解析后的 URI：", "확인된 URI:"],
		"(not available)": ["（利用不可）", "(indisponible)", "(no disponible)", "(indisponível)", "(nicht verfügbar)", "(non disponibile)", "（不可用）", "(사용 불가)"],
		"✓ API key validated successfully! Connecting...": ["✓ API キーを確認しました。接続中...", "✓ Clé API validée ! Connexion...", "✓ ¡Clave API validada! Conectando...", "✓ Chave de API validada! Conectando...", "✓ API-Schlüssel bestätigt! Verbinde...", "✓ Chiave API convalidata! Connessione...", "✓ API 密钥验证成功！正在连接...", "✓ API 키를 확인했습니다! 연결 중..."],
		"Check that the server is accessible at the correct URL": ["サーバーに正しい URL でアクセスできるか確認してください", "Vérifiez que le serveur est accessible à la bonne URL", "Comprueba que el servidor es accesible en la URL correcta", "Verifique se o servidor está acessível na URL correta", "Prüfen Sie, ob der Server unter der richtigen URL erreichbar ist", "Verifica che il server sia raggiungibile all'URL corretto", "检查服务器能否通过正确的 URL 访问", "서버가 올바른 URL로 접근 가능한지 확인하세요"],
		"Browse and attach resources from connected MCP servers to your chat context.": ["接続中の MCP サーバーのリソースを参照し、チャットに添付します。", "Parcourez et joignez des ressources des serveurs MCP connectés.", "Explora y adjunta recursos de los servidores MCP conectados.", "Navegue e anexe recursos dos servidores MCP conectados.", "Ressourcen verbundener MCP-Server durchsuchen und anhängen.", "Sfoglia e allega risorse dai server MCP connessi.", "浏览并将已连接 MCP 服务器的资源附加到聊天中。", "연결된 MCP 서버의 리소스를 찾아 채팅에 첨부합니다."],
		"Add to chat": ["チャットに追加", "Ajouter à la discussion", "Añadir al chat", "Adicionar ao chat", "Zum Chat hinzufügen", "Aggiungi alla chat", "添加到聊天", "채팅에 추가"],
		"Use Prompt": ["プロンプトを使う", "Utiliser le prompt", "Usar prompt", "Usar prompt", "Prompt verwenden", "Usa prompt", "使用提示词", "프롬프트 사용"],
		"Loading resources...": ["リソースを読み込み中...", "Chargement des ressources...", "Cargando recursos...", "Carregando recursos...", "Ressourcen werden geladen...", "Caricamento risorse...", "正在加载资源...", "리소스 로드 중..."],
		"No resources available": ["利用できるリソースがありません", "Aucune ressource disponible", "No hay recursos disponibles", "Nenhum recurso disponível", "Keine Ressourcen verfügbar", "Nessuna risorsa disponibile", "没有可用的资源", "사용 가능한 리소스가 없습니다"],
		"Read Resource": ["リソースを読む", "Lire la ressource", "Leer recurso", "Ler recurso", "Ressource lesen", "Leggi risorsa", "读取资源", "리소스 읽기"],
		"Please enter a server URL first.": ["先にサーバー URL を入力してください。", "Saisissez d'abord une URL de serveur.", "Introduce primero una URL de servidor.", "Digite primeiro uma URL de servidor.", "Bitte zuerst eine Server-URL eingeben.", "Inserisci prima l'URL del server.", "请先输入服务器 URL。", "먼저 서버 URL을 입력하세요."],
		"Search servers...": ["サーバーを検索...", "Rechercher des serveurs...", "Buscar servidores...", "Pesquisar servidores...", "Server suchen...", "Cerca server...", "搜索服务器...", "서버 검색..."],
		"Search resources...": ["リソースを検索...", "Rechercher des ressources...", "Buscar recursos...", "Pesquisar recursos...", "Ressourcen suchen...", "Cerca risorse...", "搜索资源...", "리소스 검색..."],
		"Refresh resources": ["リソースを更新", "Actualiser les ressources", "Actualizar recursos", "Atualizar recursos", "Ressourcen aktualisieren", "Aggiorna risorse", "刷新资源", "리소스 새로고침"],
		"No MCP prompts available": ["利用できる MCP プロンプトがありません", "Aucun prompt MCP disponible", "No hay prompts MCP disponibles", "Nenhum prompt MCP disponível", "Keine MCP-Prompts verfügbar", "Nessun prompt MCP disponibile", "没有可用的 MCP 提示词", "사용 가능한 MCP 프롬프트가 없습니다"],
		"No MCP resources available": ["利用できる MCP リソースがありません", "Aucune ressource MCP disponible", "No hay recursos MCP disponibles", "Nenhum recurso MCP disponível", "Keine MCP-Ressourcen verfügbar", "Nessuna risorsa MCP disponibile", "没有可用的 MCP 资源", "사용 가능한 MCP 리소스가 없습니다"],
		"No servers found": ["サーバーが見つかりません", "Aucun serveur trouvé", "No se encontraron servidores", "Nenhum servidor encontrado", "Keine Server gefunden", "Nessun server trovato", "未找到服务器", "서버를 찾을 수 없습니다"],
		"No custom headers configured.": ["カスタムヘッダーは設定されていません。", "Aucun en-tête personnalisé.", "No hay encabezados personalizados.", "Nenhum cabeçalho personalizado.", "Keine benutzerdefinierten Header.", "Nessuna intestazione personalizzata.", "未配置自定义请求头。", "사용자 지정 헤더가 없습니다."],
		"Use llama-server proxy": ["llama-server のプロキシを使う", "Utiliser le proxy llama-server", "Usar el proxy de llama-server", "Usar o proxy do llama-server", "llama-server-Proxy verwenden", "Usa il proxy di llama-server", "使用 llama-server 代理", "llama-server 프록시 사용"],
		"Set the API Key if you are using": ["サーバーで", "Définissez la clé API si vous utilisez l'option", "Configura la clave API si usas la opción", "Defina a chave de API se estiver usando a opção", "Legen Sie den API-Schlüssel fest, wenn Sie die Option", "Imposta la chiave API se usi l'opzione", "如果服务器使用了", "서버에서"],
		"option for the server.": ["オプションを使う場合は API キーを設定します。", "sur le serveur.", "en el servidor.", "no servidor.", "auf dem Server verwenden.", "sul server.", "选项，请设置 API 密钥。", "옵션을 사용하는 경우 API 키를 설정합니다."],
		"The starting message that defines how model should behave.": ["モデルの振る舞いを決める最初のメッセージです。", "Message initial qui définit le comportement du modèle.", "Mensaje inicial que define cómo debe comportarse el modelo.", "Mensagem inicial que define como o modelo deve se comportar.", "Startnachricht, die das Verhalten des Modells festlegt.", "Messaggio iniziale che definisce il comportamento del modello.", "定义模型行为方式的初始消息。", "모델의 동작을 정하는 시작 메시지입니다."],
		"Display the system message at the top of each conversation.": ["各会話の先頭にシステムメッセージを表示します。", "Affiche le message système en haut de chaque conversation.", "Muestra el mensaje del sistema al inicio de cada conversación.", "Exibe a mensagem do sistema no topo de cada conversa.", "Zeigt die Systemnachricht oben in jeder Unterhaltung an.", "Mostra il messaggio di sistema in cima a ogni conversazione.", "在每个对话顶部显示系统消息。", "각 대화 상단에 시스템 메시지를 표시합니다."],
		"Choose the color theme for the interface. You can choose between System (follows your device settings), Light, or Dark.": ["画面の配色テーマを選びます。システム（端末の設定に従う）、ライト、ダークから選べます。", "Choisissez le thème : Système (suit l'appareil), Clair ou Sombre.", "Elige el tema: Sistema (según el dispositivo), Claro u Oscuro.", "Escolha o tema: Sistema (segue o dispositivo), Claro ou Escuro.", "Farbschema wählen: System (folgt dem Gerät), Hell oder Dunkel.", "Scegli il tema: Sistema (segue il dispositivo), Chiaro o Scuro.", "选择界面配色：系统（跟随设备）、浅色或深色。", "화면 테마를 선택합니다: 시스템(기기 설정 따름), 라이트, 다크."],
		"On pasting long text, it will be converted to a file. You can control the file length by setting the value of this parameter. Value 0 means disable.": ["長いテキストを貼り付けるとファイルに変換されます。この値で変換する文字数を指定します。0 で無効。", "Un long texte collé est converti en fichier ; ce paramètre fixe la longueur. 0 = désactivé.", "Al pegar texto largo se convierte en archivo; este valor fija la longitud. 0 = desactivado.", "Ao colar texto longo, ele vira um arquivo; este valor define o tamanho. 0 = desativado.", "Langer eingefügter Text wird zur Datei; dieser Wert legt die Länge fest. 0 = aus.", "Il testo lungo incollato diventa un file; questo valore ne fissa la lunghezza. 0 = disattivato.", "粘贴长文本时会转换为文件，此值控制转换的长度。0 表示禁用。", "긴 텍스트를 붙여 넣으면 파일로 변환됩니다. 이 값으로 길이를 정합니다. 0은 사용 안 함."],
		"When copying a message with text attachments, combine them into a single plain text string instead of a special format that can be pasted back as attachments.": ["テキスト添付付きのメッセージをコピーする際、添付として貼り戻せる形式ではなく、1つのプレーンテキストにまとめます。", "Lors de la copie d'un message avec pièces jointes texte, tout combiner en texte brut.", "Al copiar un mensaje con adjuntos de texto, combinarlos en un solo texto plano.", "Ao copiar uma mensagem com anexos de texto, combiná-los em um único texto simples.", "Beim Kopieren einer Nachricht mit Textanhängen alles zu reinem Text zusammenfassen.", "Copiando un messaggio con allegati di testo, uniscili in un unico testo semplice.", "复制带文本附件的消息时，将其合并为一段纯文本。", "텍스트 첨부가 있는 메시지를 복사할 때 하나의 일반 텍스트로 합칩니다."],
		"Enable backend-based samplers. When enabled, supported samplers run on the accelerator backend for faster sampling.": ["バックエンド側のサンプラーを使います。対応するサンプラーがアクセラレータ上で動作し高速化します。", "Active les échantillonneurs côté backend, exécutés sur l'accélérateur pour plus de rapidité.", "Activa los muestreadores en el backend para un muestreo más rápido en el acelerador.", "Ativa amostradores no backend, executados no acelerador para maior velocidade.", "Aktiviert Backend-Sampler, die für schnelleres Sampling auf dem Beschleuniger laufen.", "Abilita i campionatori sul backend, eseguiti sull'acceleratore per maggiore velocità.", "启用后端采样器，受支持的采样器会在加速器上运行以加快采样。", "백엔드 샘플러를 사용합니다. 지원되는 샘플러가 가속기에서 실행되어 빨라집니다."],
		"Controls the randomness of the generated text by affecting the probability distribution of the output tokens. Higher = more random, lower = more focused.": ["出力トークンの確率分布を調整して生成のランダム性を制御します。高いほどランダム、低いほど安定します。", "Contrôle le caractère aléatoire du texte. Plus haut = plus aléatoire, plus bas = plus ciblé.", "Controla la aleatoriedad del texto. Más alto = más aleatorio, más bajo = más centrado.", "Controla a aleatoriedade do texto. Maior = mais aleatório, menor = mais focado.", "Steuert die Zufälligkeit des Textes. Höher = zufälliger, niedriger = fokussierter.", "Controlla la casualità del testo. Più alto = più casuale, più basso = più mirato.", "通过调整输出令牌的概率分布控制随机性。越高越随机，越低越集中。", "출력 토큰의 확률 분포를 조정해 무작위성을 제어합니다. 높을수록 무작위, 낮을수록 안정적."],
		"Addon for the temperature sampler. The added value to the range of dynamic temperature, which adjusts probabilities by entropy of tokens.": ["温度サンプラーの拡張です。トークンのエントロピーに応じて確率を調整する動的温度の幅です。", "Extension du sampler de température : plage de température dynamique selon l'entropie.", "Complemento del muestreador de temperatura: rango de temperatura dinámica según la entropía.", "Complemento do amostrador de temperatura: faixa de temperatura dinâmica pela entropia.", "Erweiterung des Temperatur-Samplers: Bereich der dynamischen Temperatur nach Entropie.", "Estensione del campionatore di temperatura: intervallo di temperatura dinamica secondo l'entropia.", "温度采样器的附加项：按令牌熵调整概率的动态温度范围。", "온도 샘플러 확장: 토큰 엔트로피에 따라 확률을 조정하는 동적 온도 범위입니다."],
		"Addon for the temperature sampler. Smoothes out the probability redistribution based on the most probable token.": ["温度サンプラーの拡張です。最も確率の高いトークンを基準に確率の再分配を滑らかにします。", "Extension du sampler de température : lisse la redistribution des probabilités.", "Complemento del muestreador de temperatura: suaviza la redistribución de probabilidades.", "Complemento do amostrador de temperatura: suaviza a redistribuição de probabilidades.", "Erweiterung des Temperatur-Samplers: glättet die Neuverteilung der Wahrscheinlichkeiten.", "Estensione del campionatore di temperatura: attenua la ridistribuzione delle probabilità.", "温度采样器的附加项：以最可能的令牌为基准平滑概率重分配。", "온도 샘플러 확장: 가장 확률 높은 토큰을 기준으로 확률 재분배를 완화합니다."],
		"Keeps only k top tokens.": ["上位 k 個のトークンだけを残します。", "Ne garde que les k meilleurs jetons.", "Conserva solo los k tokens principales.", "Mantém apenas os k tokens principais.", "Behält nur die k besten Tokens.", "Mantiene solo i primi k token.", "仅保留前 k 个令牌。", "상위 k개의 토큰만 남깁니다."],
		"Limits tokens to those that together have a cumulative probability of at least p": ["累積確率が p 以上になる範囲のトークンに制限します", "Limite aux jetons dont la probabilité cumulée atteint au moins p", "Limita a los tokens cuya probabilidad acumulada llega al menos a p", "Limita aos tokens cuja probabilidade acumulada chega a pelo menos p", "Beschränkt auf Tokens mit kumulierter Wahrscheinlichkeit von mindestens p", "Limita ai token con probabilità cumulativa di almeno p", "仅保留累计概率至少为 p 的令牌", "누적 확률이 p 이상이 되는 토큰으로 제한합니다"],
		"Limits tokens based on the minimum probability for a token to be considered, relative to the probability of the most likely token.": ["最も確率の高いトークンに対する相対的な最小確率でトークンを制限します。", "Limite les jetons selon une probabilité minimale relative au jeton le plus probable.", "Limita los tokens por una probabilidad mínima relativa al token más probable.", "Limita os tokens por uma probabilidade mínima relativa ao token mais provável.", "Beschränkt Tokens nach einer Mindestwahrscheinlichkeit relativ zum wahrscheinlichsten Token.", "Limita i token con una probabilità minima relativa al token più probabile.", "按相对于最可能令牌的最低概率限制令牌。", "가장 확률 높은 토큰 대비 최소 확률로 토큰을 제한합니다."],
		"XTC sampler cuts out top tokens; this parameter controls the chance of cutting tokens at all. 0 disables XTC.": ["XTC は上位トークンを除外するサンプラーです。この値は除外を行う確率です。0 で無効。", "XTC retire les meilleurs jetons ; ce paramètre fixe la probabilité de le faire. 0 = désactivé.", "XTC elimina los tokens principales; esto fija la probabilidad de hacerlo. 0 = desactivado.", "XTC remove os tokens principais; isto define a chance de remover. 0 = desativado.", "XTC entfernt Top-Tokens; dies legt die Wahrscheinlichkeit dafür fest. 0 = aus.", "XTC rimuove i token migliori; questo valore fissa la probabilità di farlo. 0 = disattivato.", "XTC 会剔除排名靠前的令牌；此参数控制剔除的概率。0 为禁用。", "XTC는 상위 토큰을 제외합니다. 이 값은 제외할 확률입니다. 0은 사용 안 함."],
		"XTC sampler cuts out top tokens; this parameter controls the token probability that is required to cut that token.": ["XTC は上位トークンを除外するサンプラーです。この値は除外対象となるトークン確率のしきい値です。", "XTC retire les meilleurs jetons ; ce paramètre fixe la probabilité requise pour retirer un jeton.", "XTC elimina los tokens principales; esto fija la probabilidad necesaria para eliminarlos.", "XTC remove os tokens principais; isto define a probabilidade necessária para remover.", "XTC entfernt Top-Tokens; dies legt die dafür nötige Token-Wahrscheinlichkeit fest.", "XTC rimuove i token migliori; questo valore fissa la probabilità necessaria per rimuoverli.", "XTC 会剔除排名靠前的令牌；此参数控制被剔除所需的令牌概率。", "XTC는 상위 토큰을 제외합니다. 이 값은 제외 대상이 되는 확률 임계값입니다."],
		"Sorts and limits tokens based on the difference between log-probability and entropy.": ["対数確率とエントロピーの差に基づいてトークンを並べ替え・制限します。", "Trie et limite les jetons selon l'écart entre log-probabilité et entropie.", "Ordena y limita los tokens según la diferencia entre log-probabilidad y entropía.", "Ordena e limita tokens pela diferença entre log-probabilidade e entropia.", "Sortiert und begrenzt Tokens nach Differenz von Log-Wahrscheinlichkeit und Entropie.", "Ordina e limita i token in base alla differenza tra log-probabilità ed entropia.", "根据对数概率与熵的差值对令牌排序并限制。", "로그 확률과 엔트로피의 차이로 토큰을 정렬·제한합니다."],
		"Last n tokens to consider for penalizing repetition": ["繰り返しペナルティの対象とする直近のトークン数", "Nombre de derniers jetons pris en compte pour pénaliser la répétition", "Últimos n tokens considerados para penalizar la repetición", "Últimos n tokens considerados para penalizar a repetição", "Letzte n Tokens für die Wiederholungsstrafe", "Ultimi n token considerati per penalizzare la ripetizione", "用于重复惩罚的最近令牌数", "반복 페널티에 고려할 최근 토큰 수"],
		"Controls the repetition of token sequences in the generated text": ["生成テキスト内のトークン列の繰り返しを抑制します", "Contrôle la répétition des séquences de jetons", "Controla la repetición de secuencias de tokens", "Controla a repetição de sequências de tokens", "Steuert die Wiederholung von Token-Folgen", "Controlla la ripetizione di sequenze di token", "控制生成文本中令牌序列的重复", "생성 텍스트에서 토큰 시퀀스의 반복을 제어합니다"],
		"Limits tokens based on whether they appear in the output or not.": ["出力に既に現れたかどうかでトークンを抑制します。", "Limite les jetons selon qu'ils apparaissent déjà dans la sortie.", "Limita los tokens según si ya aparecen en la salida.", "Limita os tokens conforme já aparecem na saída.", "Begrenzt Tokens danach, ob sie bereits in der Ausgabe vorkommen.", "Limita i token in base alla loro presenza nell'output.", "根据令牌是否已在输出中出现进行限制。", "출력에 이미 나타났는지에 따라 토큰을 제한합니다."],
		"Limits tokens based on how often they appear in the output.": ["出力に現れた頻度に応じてトークンを抑制します。", "Limite les jetons selon leur fréquence dans la sortie.", "Limita los tokens según su frecuencia en la salida.", "Limita os tokens conforme a frequência na saída.", "Begrenzt Tokens nach ihrer Häufigkeit in der Ausgabe.", "Limita i token in base alla frequenza nell'output.", "根据令牌在输出中出现的频率进行限制。", "출력에 나타난 빈도에 따라 토큰을 제한합니다."],
		"DRY sampling reduces repetition in generated text even across long contexts. This parameter sets the DRY sampling multiplier.": ["DRY サンプリングは長いコンテキストでも繰り返しを減らします。この値は DRY の乗数です。", "DRY réduit la répétition même sur de longs contextes. Ce paramètre est le multiplicateur DRY.", "DRY reduce la repetición incluso en contextos largos. Este es el multiplicador DRY.", "DRY reduz a repetição mesmo em contextos longos. Este é o multiplicador DRY.", "DRY reduziert Wiederholungen auch über lange Kontexte. Dies ist der DRY-Multiplikator.", "DRY riduce la ripetizione anche su contesti lunghi. Questo è il moltiplicatore DRY.", "DRY 采样可在长上下文中减少重复。此参数为 DRY 乘数。", "DRY 샘플링은 긴 컨텍스트에서도 반복을 줄입니다. 이 값은 DRY 배수입니다."],
		"DRY sampling reduces repetition in generated text even across long contexts. This parameter sets the DRY sampling base value.": ["DRY サンプリングは長いコンテキストでも繰り返しを減らします。この値は DRY の基数です。", "DRY réduit la répétition même sur de longs contextes. Ce paramètre est la base DRY.", "DRY reduce la repetición incluso en contextos largos. Este es el valor base DRY.", "DRY reduz a repetição mesmo em contextos longos. Este é o valor base DRY.", "DRY reduziert Wiederholungen auch über lange Kontexte. Dies ist der DRY-Basiswert.", "DRY riduce la ripetizione anche su contesti lunghi. Questo è il valore base DRY.", "DRY 采样可在长上下文中减少重复。此参数为 DRY 基数。", "DRY 샘플링은 긴 컨텍스트에서도 반복을 줄입니다. 이 값은 DRY 기본값입니다."],
		"DRY sampling reduces repetition in generated text even across long contexts. This parameter sets the allowed length for DRY sampling.": ["DRY サンプリングは長いコンテキストでも繰り返しを減らします。この値は DRY で許容する長さです。", "DRY réduit la répétition même sur de longs contextes. Ce paramètre est la longueur autorisée.", "DRY reduce la repetición incluso en contextos largos. Esta es la longitud permitida.", "DRY reduz a repetição mesmo em contextos longos. Este é o comprimento permitido.", "DRY reduziert Wiederholungen auch über lange Kontexte. Dies ist die erlaubte Länge.", "DRY riduce la ripetizione anche su contesti lunghi. Questa è la lunghezza consentita.", "DRY 采样可在长上下文中减少重复。此参数为允许长度。", "DRY 샘플링은 긴 컨텍스트에서도 반복을 줄입니다. 이 값은 허용 길이입니다."],
		"DRY sampling reduces repetition in generated text even across long contexts. This parameter sets DRY penalty for the last n tokens.": ["DRY サンプリングは長いコンテキストでも繰り返しを減らします。この値は DRY ペナルティの対象とする直近トークン数です。", "DRY réduit la répétition même sur de longs contextes. Ce paramètre fixe la pénalité sur les n derniers jetons.", "DRY reduce la repetición incluso en contextos largos. Fija la penalización de los últimos n tokens.", "DRY reduz a repetição mesmo em contextos longos. Define a penalidade dos últimos n tokens.", "DRY reduziert Wiederholungen auch über lange Kontexte. Legt die Strafe für die letzten n Tokens fest.", "DRY riduce la ripetizione anche su contesti lunghi. Fissa la penalità sugli ultimi n token.", "DRY 采样可在长上下文中减少重复。此参数设置最近 n 个令牌的惩罚。", "DRY 샘플링은 긴 컨텍스트에서도 반복을 줄입니다. 최근 n개 토큰에 대한 페널티입니다."],
		"The maximum number of token per output. Use -1 for infinite (no limit).": ["1回の出力の最大トークン数です。-1 で無制限。", "Nombre maximal de jetons par sortie. -1 = illimité.", "Número máximo de tokens por salida. -1 = sin límite.", "Número máximo de tokens por saída. -1 = sem limite.", "Maximale Tokens pro Ausgabe. -1 = unbegrenzt.", "Numero massimo di token per output. -1 = illimitato.", "每次输出的最大令牌数。-1 表示不限制。", "출력당 최대 토큰 수입니다. -1은 무제한."],
		"Custom JSON parameters to send to the API. Must be valid JSON format.": ["API に送る追加の JSON パラメータです。正しい JSON 形式で指定してください。", "Paramètres JSON personnalisés envoyés à l'API. JSON valide requis.", "Parámetros JSON personalizados para la API. Debe ser JSON válido.", "Parâmetros JSON personalizados para a API. Deve ser JSON válido.", "Benutzerdefinierte JSON-Parameter für die API. Gültiges JSON erforderlich.", "Parametri JSON personalizzati per l'API. Deve essere JSON valido.", "发送给 API 的自定义 JSON 参数，必须是有效的 JSON。", "API에 보낼 사용자 지정 JSON 매개변수입니다. 올바른 JSON이어야 합니다."],
		"Expand thought process by default when generating messages.": ["生成中は思考過程を既定で展開して表示します。", "Déplie le raisonnement par défaut pendant la génération.", "Expande el razonamiento por defecto al generar.", "Expande o raciocínio por padrão ao gerar.", "Klappt den Denkprozess beim Generieren standardmäßig auf.", "Espande il ragionamento per impostazione predefinita durante la generazione.", "生成时默认展开思考过程。", "생성 중 사고 과정을 기본으로 펼쳐 표시합니다."],
		"Send reasoning_format=none to prevent server-side extraction of reasoning tokens into separate field": ["reasoning_format=none を送り、サーバー側で思考トークンを別フィールドに分離しないようにします", "Envoie reasoning_format=none pour ne pas séparer le raisonnement côté serveur", "Envía reasoning_format=none para que el servidor no separe el razonamiento", "Envia reasoning_format=none para o servidor não separar o raciocínio", "Sendet reasoning_format=none, damit der Server den Denkprozess nicht abtrennt", "Invia reasoning_format=none per non separare il ragionamento sul server", "发送 reasoning_format=none，阻止服务器将推理内容分离到单独字段", "reasoning_format=none을 보내 서버가 추론 토큰을 별도 필드로 분리하지 않게 합니다"],
		"Strip reasoning content from previous messages before sending to the model. When unchecked, reasoning is sent back via the reasoning_content field so the model can see its own chain-of-thought across turns.": ["送信前に過去のメッセージから思考内容を取り除きます。オフにすると reasoning_content として送り返し、モデルが前の思考を参照できます。", "Retire le raisonnement des messages précédents avant l'envoi. Décoché, il est renvoyé via reasoning_content.", "Quita el razonamiento de mensajes anteriores antes de enviar. Desmarcado, se reenvía vía reasoning_content.", "Remove o raciocínio das mensagens anteriores antes de enviar. Desmarcado, é reenviado via reasoning_content.", "Entfernt den Denkprozess früherer Nachrichten vor dem Senden. Deaktiviert wird er über reasoning_content zurückgesendet.", "Rimuove il ragionamento dai messaggi precedenti prima dell'invio. Se disattivato, viene rinviato via reasoning_content.", "发送前从之前的消息中移除推理内容。取消勾选时通过 reasoning_content 回传，模型可看到之前的思考。", "전송 전에 이전 메시지의 추론 내용을 제거합니다. 끄면 reasoning_content로 다시 보내 모델이 이전 사고를 볼 수 있습니다."],
		"Show toggle button to display messages as plain text instead of Markdown-formatted content": ["メッセージを Markdown ではなくプレーンテキストで表示する切替ボタンを表示します", "Affiche un bouton pour voir les messages en texte brut au lieu de Markdown", "Muestra un botón para ver los mensajes como texto plano en vez de Markdown", "Mostra um botão para ver as mensagens como texto simples em vez de Markdown", "Zeigt einen Schalter, um Nachrichten als reinen Text statt Markdown anzuzeigen", "Mostra un pulsante per vedere i messaggi come testo semplice invece di Markdown", "显示切换按钮，以纯文本而非 Markdown 显示消息", "메시지를 Markdown 대신 일반 텍스트로 표시하는 전환 버튼을 표시합니다"],
		"Keep processing statistics visible after generation finishes.": ["生成完了後も処理統計を表示したままにします。", "Garde les statistiques visibles après la génération.", "Mantiene las estadísticas visibles al terminar la generación.", "Mantém as estatísticas visíveis após a geração.", "Statistik nach Abschluss der Generierung sichtbar lassen.", "Mantiene visibili le statistiche dopo la generazione.", "生成结束后仍显示处理统计。", "생성이 끝난 후에도 처리 통계를 계속 표시합니다."],
		"Display generation statistics (tokens/second, token count, duration) below each assistant message.": ["各アシスタントメッセージの下に生成統計（トークン/秒、トークン数、所要時間）を表示します。", "Affiche les statistiques (jetons/s, nombre, durée) sous chaque réponse.", "Muestra estadísticas (tokens/s, cantidad, duración) bajo cada respuesta.", "Mostra estatísticas (tokens/s, quantidade, duração) abaixo de cada resposta.", "Zeigt Statistik (Tokens/s, Anzahl, Dauer) unter jeder Antwort.", "Mostra le statistiche (token/s, numero, durata) sotto ogni risposta.", "在每条助手消息下方显示生成统计（令牌/秒、令牌数、耗时）。", "각 어시스턴트 메시지 아래에 생성 통계(토큰/초, 토큰 수, 시간)를 표시합니다."],
		"Ask for confirmation before automatically changing conversation title when editing the first message.": ["最初のメッセージを編集したとき、会話タイトルを自動変更する前に確認します。", "Demande confirmation avant de changer le titre après modification du premier message.", "Pide confirmación antes de cambiar el título al editar el primer mensaje.", "Pede confirmação antes de alterar o título ao editar a primeira mensagem.", "Vor dem automatischen Ändern des Titels beim Bearbeiten der ersten Nachricht nachfragen.", "Chiede conferma prima di cambiare il titolo modificando il primo messaggio.", "编辑第一条消息时，在自动更改对话标题前先确认。", "첫 메시지를 편집할 때 대화 제목을 자동 변경하기 전에 확인합니다."],
		"Parse PDF as image instead of text. Automatically falls back to text processing for non-vision models.": ["PDF をテキストではなく画像として解析します。画像非対応モデルでは自動的にテキスト処理になります。", "Analyse les PDF comme images ; texte automatiquement pour les modèles sans vision.", "Analiza los PDF como imágenes; usa texto con modelos sin visión.", "Analisa PDFs como imagens; usa texto com modelos sem visão.", "PDFs als Bilder verarbeiten; bei Modellen ohne Vision automatisch als Text.", "Analizza i PDF come immagini; testo automatico per modelli senza visione.", "将 PDF 作为图像而非文本解析；非视觉模型会自动改用文本处理。", "PDF를 텍스트 대신 이미지로 분석합니다. 비전 미지원 모델은 자동으로 텍스트 처리합니다."],
		"Disable automatic scrolling while messages stream so you can control the viewport position manually.": ["メッセージ出力中の自動スクロールを止め、表示位置を手動で操作できるようにします。", "Désactive le défilement auto pendant le streaming pour contrôler la vue.", "Desactiva el desplazamiento automático durante el streaming.", "Desativa a rolagem automática durante o streaming.", "Deaktiviert automatisches Scrollen während des Streamings.", "Disattiva lo scorrimento automatico durante lo streaming.", "在消息流式输出时禁用自动滚动，以便手动控制视图位置。", "메시지 출력 중 자동 스크롤을 끄고 위치를 직접 조절합니다."],
		"Render user messages using markdown formatting in the chat.": ["チャット内のユーザーメッセージを Markdown で表示します。", "Affiche les messages utilisateur en Markdown.", "Muestra los mensajes del usuario con formato Markdown.", "Exibe as mensagens do usuário com formatação Markdown.", "Benutzernachrichten als Markdown darstellen.", "Mostra i messaggi utente con formattazione Markdown.", "在聊天中以 Markdown 渲染用户消息。", "채팅에서 사용자 메시지를 Markdown으로 표시합니다."],
		"Always keep the sidebar visible on desktop instead of auto-hiding it.": ["デスクトップではサイドバーを自動で隠さず常に表示します。", "Garde la barre latérale visible sur ordinateur.", "Mantiene la barra lateral visible en escritorio.", "Mantém a barra lateral visível no desktop.", "Seitenleiste auf dem Desktop immer sichtbar lassen.", "Mantiene sempre visibile la barra laterale su desktop.", "桌面端始终显示侧边栏，而不是自动隐藏。", "데스크톱에서 사이드바를 자동으로 숨기지 않고 항상 표시합니다."],
		"Automatically show sidebar when starting a new chat. Disable to keep the sidebar hidden until you click on it.": ["新しいチャットを始めるとサイドバーを自動表示します。オフにするとクリックするまで隠れたままです。", "Affiche la barre latérale à chaque nouvelle discussion. Désactivé, elle reste masquée.", "Muestra la barra lateral al iniciar un chat. Desactivado, queda oculta hasta pulsarla.", "Mostra a barra lateral ao iniciar um chat. Desativado, fica oculta até clicar.", "Seitenleiste bei neuem Chat anzeigen. Deaktiviert bleibt sie bis zum Klick verborgen.", "Mostra la barra laterale nelle nuove chat. Disattivato, resta nascosta finché non la apri.", "开始新聊天时自动显示侧边栏。关闭后需点击才会显示。", "새 채팅을 시작하면 사이드바를 자동으로 표시합니다. 끄면 누를 때까지 숨겨집니다."],
		"Automatically show microphone button instead of send button when textarea is empty for models with audio modality support.": ["音声対応モデルでは、入力欄が空のとき送信ボタンの代わりにマイクボタンを表示します。", "Affiche le micro au lieu d'Envoyer quand la saisie est vide (modèles audio).", "Muestra el micrófono en lugar de Enviar con la entrada vacía (modelos de audio).", "Mostra o microfone em vez de Enviar com entrada vazia (modelos de áudio).", "Zeigt bei leerer Eingabe das Mikrofon statt Senden (Audio-Modelle).", "Mostra il microfono invece di Invia con input vuoto (modelli audio).", "对支持音频的模型，输入框为空时显示麦克风按钮代替发送按钮。", "오디오 지원 모델에서 입력이 비어 있으면 보내기 대신 마이크 버튼을 표시합니다."],
		"Always display code blocks at their full natural height, overriding any height limits.": ["高さ制限を無視して、コードブロックを常に本来の高さで表示します。", "Affiche toujours les blocs de code en pleine hauteur.", "Muestra siempre los bloques de código a altura completa.", "Sempre exibe blocos de código em altura total.", "Codeblöcke immer in voller Höhe anzeigen.", "Mostra sempre i blocchi di codice a tutta altezza.", "始终以完整高度显示代码块，忽略高度限制。", "높이 제한 없이 코드 블록을 항상 전체 높이로 표시합니다."],
		"Configure MCP servers as a JSON list. Use the form in the MCP Client settings section to edit.": ["MCP サーバーを JSON のリストで設定します。編集は MCP クライアント設定のフォームで行います。", "Configure les serveurs MCP en liste JSON ; modifiez-les via le formulaire MCP.", "Configura los servidores MCP como lista JSON; edítalos en el formulario MCP.", "Configura os servidores MCP como lista JSON; edite pelo formulário MCP.", "MCP-Server als JSON-Liste konfigurieren; Bearbeitung im MCP-Formular.", "Configura i server MCP come elenco JSON; modificali dal modulo MCP.", "以 JSON 列表配置 MCP 服务器，请在 MCP 客户端设置的表单中编辑。", "MCP 서버를 JSON 목록으로 설정합니다. 편집은 MCP 클라이언트 설정 양식에서 합니다."],
		"Maximum number of tool execution cycles before stopping (prevents infinite loops).": ["停止するまでのツール実行サイクルの最大数です（無限ループ防止）。", "Nombre max. de cycles d'outils avant arrêt (évite les boucles infinies).", "Máximo de ciclos de herramientas antes de parar (evita bucles infinitos).", "Máximo de ciclos de ferramentas antes de parar (evita loops infinitos).", "Max. Tool-Zyklen vor dem Stopp (verhindert Endlosschleifen).", "Numero massimo di cicli di strumenti prima dell'arresto (evita cicli infiniti).", "停止前工具执行循环的最大次数（防止无限循环）。", "중지 전 최대 도구 실행 횟수입니다 (무한 루프 방지)."],
		"Automatically expand tool call details while executing and keep them expanded after completion.": ["ツール実行中は詳細を自動で展開し、完了後も展開したままにします。", "Déplie les détails des appels d'outils pendant et après l'exécution.", "Expande los detalles de las llamadas a herramientas durante y después.", "Expande os detalhes das chamadas de ferramentas durante e depois.", "Klappt Tool-Aufrufdetails während und nach der Ausführung auf.", "Espande i dettagli delle chiamate agli strumenti durante e dopo.", "执行时自动展开工具调用详情，完成后保持展开。", "도구 실행 중 세부 정보를 자동으로 펼치고 완료 후에도 유지합니다."],
		"Enable Python interpreter using Pyodide. Allows running Python code in markdown code blocks.": ["Pyodide による Python インタプリタを有効にし、コードブロック内の Python を実行できるようにします。", "Active l'interpréteur Python (Pyodide) pour exécuter les blocs de code.", "Activa el intérprete de Python (Pyodide) para ejecutar bloques de código.", "Ativa o interpretador Python (Pyodide) para executar blocos de código.", "Aktiviert den Python-Interpreter (Pyodide) für Codeblöcke.", "Abilita l'interprete Python (Pyodide) per eseguire i blocchi di codice.", "启用基于 Pyodide 的 Python 解释器，可运行代码块中的 Python。", "Pyodide 기반 Python 인터프리터를 켜서 코드 블록의 Python을 실행합니다."],
		// ---- MCP / developer review 2026-11-24
		"Access denied - check server permissions": ["アクセスが拒否されました - サーバーの権限を確認してください", "Accès refusé - vérifiez les autorisations du serveur", "Acceso denegado: revisa los permisos del servidor", "Acesso negado - verifique as permissões do servidor", "Zugriff verweigert – Serverberechtigungen prüfen", "Accesso negato - controlla i permessi del server", "访问被拒绝 - 请检查服务器权限", "접근이 거부되었습니다 - 서버 권한을 확인하세요"],
		"Add files, system prompt or MCP Servers": ["ファイル・システムプロンプト・MCP サーバーを追加", "Ajouter des fichiers, un prompt système ou des serveurs MCP", "Añadir archivos, prompt del sistema o servidores MCP", "Adicionar arquivos, prompt do sistema ou servidores MCP", "Dateien, Systemprompt oder MCP-Server hinzufügen", "Aggiungi file, prompt di sistema o server MCP", "添加文件、系统提示词或 MCP 服务器", "파일·시스템 프롬프트·MCP 서버 추가"],
		"All MCP server connections failed": ["すべての MCP サーバーへの接続に失敗しました", "Échec de toutes les connexions aux serveurs MCP", "Fallaron todas las conexiones a servidores MCP", "Todas as conexões com servidores MCP falharam", "Alle MCP-Serververbindungen fehlgeschlagen", "Tutte le connessioni ai server MCP non riuscite", "所有 MCP 服务器连接均失败", "모든 MCP 서버 연결에 실패했습니다"],
		"Cannot connect to server - check if server is running": ["サーバーに接続できません - サーバーが起動しているか確認してください", "Connexion impossible - vérifiez que le serveur tourne", "No se puede conectar: comprueba que el servidor esté en marcha", "Não foi possível conectar - verifique se o servidor está em execução", "Keine Verbindung – läuft der Server?", "Impossibile connettersi - verifica che il server sia attivo", "无法连接服务器 - 请检查服务器是否在运行", "서버에 연결할 수 없습니다 - 서버가 실행 중인지 확인하세요"],
		"Connected": ["接続済み", "Connecté", "Conectado", "Conectado", "Verbunden", "Connesso", "已连接", "연결됨"],
		"Not connected": ["未接続", "Non connecté", "No conectado", "Não conectado", "Nicht verbunden", "Non connesso", "未连接", "연결 안 됨"],
		"Connection Error": ["接続エラー", "Erreur de connexion", "Error de conexión", "Erro de conexão", "Verbindungsfehler", "Errore di connessione", "连接错误", "연결 오류"],
		"Connection error - please try again": ["接続エラー - もう一度お試しください", "Erreur de connexion - réessayez", "Error de conexión: inténtalo de nuevo", "Erro de conexão - tente novamente", "Verbindungsfehler – bitte erneut versuchen", "Errore di connessione - riprova", "连接错误 - 请重试", "연결 오류 - 다시 시도하세요"],
		"Connection refused - server may be offline": ["接続が拒否されました - サーバーが停止している可能性があります", "Connexion refusée - le serveur est peut-être hors ligne", "Conexión rechazada: el servidor puede estar desconectado", "Conexão recusada - o servidor pode estar offline", "Verbindung abgelehnt – Server evtl. offline", "Connessione rifiutata - il server potrebbe essere offline", "连接被拒绝 - 服务器可能已离线", "연결이 거부되었습니다 - 서버가 꺼져 있을 수 있습니다"],
		"Connection timed out - API server may be disabled or blocked by a firewall app": ["接続がタイムアウトしました - API サーバーが無効か、ファイアウォールアプリにブロックされている可能性があります", "Délai dépassé - serveur API désactivé ou bloqué par un pare-feu", "Tiempo agotado: el servidor API puede estar desactivado o bloqueado por un firewall", "Tempo esgotado - o servidor API pode estar desativado ou bloqueado por firewall", "Zeitüberschreitung – API-Server deaktiviert oder durch Firewall blockiert", "Timeout - server API disattivato o bloccato da un firewall", "连接超时 - API 服务器可能已关闭或被防火墙应用阻止", "연결 시간 초과 - API 서버가 꺼져 있거나 방화벽 앱에 차단되었을 수 있습니다"],
		"Connection closed before the response completed (the server may have stopped, restarted, or the request was cancelled).": ["応答の完了前に接続が切れました（サーバーの停止・再起動、またはリクエストの取り消しの可能性があります）。", "Connexion fermée avant la fin de la réponse (serveur arrêté, redémarré ou requête annulée).", "La conexión se cerró antes de completar la respuesta (servidor detenido, reiniciado o solicitud cancelada).", "A conexão foi encerrada antes do fim da resposta (servidor parado, reiniciado ou solicitação cancelada).", "Verbindung vor Abschluss der Antwort getrennt (Server gestoppt, neu gestartet oder Anfrage abgebrochen).", "Connessione chiusa prima del completamento (server arrestato, riavviato o richiesta annullata).", "响应完成前连接已关闭（服务器可能已停止、重启，或请求被取消）。", "응답이 끝나기 전에 연결이 끊겼습니다 (서버 중지·재시작 또는 요청 취소 가능성)."],
		"Custom Headers": ["カスタムヘッダー", "En-têtes personnalisés", "Encabezados personalizados", "Cabeçalhos personalizados", "Benutzerdefinierte Header", "Intestazioni personalizzate", "自定义请求头", "사용자 지정 헤더"],
		"Header name": ["ヘッダー名", "Nom de l'en-tête", "Nombre del encabezado", "Nome do cabeçalho", "Header-Name", "Nome intestazione", "请求头名称", "헤더 이름"],
		"Value": ["値", "Valeur", "Valor", "Valor", "Wert", "Valore", "值", "값"],
		"Headers": ["ヘッダー", "En-têtes", "Encabezados", "Cabeçalhos", "Header", "Intestazioni", "请求头", "헤더"],
		"Debug": ["デバッグ", "Débogage", "Depuración", "Depuração", "Debug", "Debug", "调试", "디버그"],
		"Context": ["コンテキスト", "Contexte", "Contexto", "Contexto", "Kontext", "Contesto", "上下文", "컨텍스트"],
		"Display full raw model identifiers (e.g. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") instead of parsed names with badges.": ["バッジ付きの整形名ではなく、モデル ID をそのまま表示します（例: \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\"）。", "Affiche les identifiants bruts complets des modèles (ex. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") au lieu des noms avec badges.", "Muestra los identificadores completos (p. ej. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") en lugar de nombres con insignias.", "Mostra os identificadores completos (ex. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") em vez de nomes com selos.", "Zeigt vollständige Modell-IDs (z. B. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") statt Namen mit Badges.", "Mostra gli identificativi completi (es. \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\") invece dei nomi con badge.", "显示完整的原始模型标识（如 \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\"），而不是带徽章的解析名称。", "배지가 붙은 이름 대신 전체 모델 ID를 그대로 표시합니다 (예: \"ggml-org/GLM-4.7-Flash-GGUF:Q8_0\")."],
		"Failed to connect to server": ["サーバーに接続できませんでした", "Échec de connexion au serveur", "No se pudo conectar al servidor", "Falha ao conectar ao servidor", "Verbindung zum Server fehlgeschlagen", "Connessione al server non riuscita", "连接服务器失败", "서버에 연결하지 못했습니다"],
		"Failed to load resource content": ["リソースの内容を読み込めませんでした", "Impossible de charger le contenu de la ressource", "No se pudo cargar el contenido del recurso", "Falha ao carregar o conteúdo do recurso", "Ressourceninhalt konnte nicht geladen werden", "Impossibile caricare il contenuto della risorsa", "加载资源内容失败", "리소스 내용을 불러오지 못했습니다"],
		"Failed to read resource": ["リソースを読めませんでした", "Impossible de lire la ressource", "No se pudo leer el recurso", "Falha ao ler o recurso", "Ressource konnte nicht gelesen werden", "Impossibile leggere la risorsa", "读取资源失败", "리소스를 읽지 못했습니다"],
		"Generation (token output)": ["生成（トークン出力）", "Génération (sortie des jetons)", "Generación (salida de tokens)", "Geração (saída de tokens)", "Generierung (Token-Ausgabe)", "Generazione (output dei token)", "生成（令牌输出）", "생성 (토큰 출력)"],
		"Generation (waiting for tokens...)": ["生成（トークン待ち...）", "Génération (attente des jetons...)", "Generación (esperando tokens...)", "Geração (aguardando tokens...)", "Generierung (warte auf Tokens...)", "Generazione (in attesa dei token...)", "生成（等待令牌...）", "생성 (토큰 대기 중...)"],
		"Initializing connection to llama.cpp server...": ["llama.cpp サーバーへの接続を初期化中...", "Initialisation de la connexion au serveur llama.cpp...", "Iniciando la conexión con el servidor llama.cpp...", "Iniciando a conexão com o servidor llama.cpp...", "Verbindung zum llama.cpp-Server wird initialisiert...", "Inizializzazione della connessione al server llama.cpp...", "正在初始化与 llama.cpp 服务器的连接...", "llama.cpp 서버 연결 초기화 중..."],
		"Invalid JSON in custom parameters. Please check the format and try again.": ["カスタムパラメータの JSON が正しくありません。形式を確認してもう一度お試しください。", "JSON invalide dans les paramètres personnalisés. Vérifiez le format.", "JSON no válido en los parámetros personalizados. Revisa el formato.", "JSON inválido nos parâmetros personalizados. Verifique o formato.", "Ungültiges JSON in benutzerdefinierten Parametern. Format prüfen.", "JSON non valido nei parametri personalizzati. Controlla il formato.", "自定义参数中的 JSON 无效，请检查格式后重试。", "사용자 지정 매개변수의 JSON이 올바르지 않습니다. 형식을 확인하세요."],
		"Listing available tools...": ["利用可能なツールを取得中...", "Liste des outils disponibles...", "Listando herramientas disponibles...", "Listando ferramentas disponíveis...", "Verfügbare Tools werden aufgelistet...", "Elenco degli strumenti disponibili...", "正在列出可用工具...", "사용 가능한 도구 조회 중..."],
		"Sending initialize request...": ["初期化リクエストを送信中...", "Envoi de la requête d'initialisation...", "Enviando solicitud de inicialización...", "Enviando solicitação de inicialização...", "Initialisierungsanfrage wird gesendet...", "Invio della richiesta di inizializzazione...", "正在发送初始化请求...", "초기화 요청 전송 중..."],
		"Capabilities exchanged successfully": ["機能情報の交換が完了しました", "Capacités échangées", "Capacidades intercambiadas correctamente", "Recursos trocados com sucesso", "Fähigkeiten erfolgreich ausgetauscht", "Capacità scambiate correttamente", "已成功交换功能信息", "기능 정보 교환 완료"],
		"MCP Resource": ["MCP リソース", "Ressource MCP", "Recurso MCP", "Recurso MCP", "MCP-Ressource", "Risorsa MCP", "MCP 资源", "MCP 리소스"],
		"MCP server configuration is missing url": ["MCP サーバーの設定に URL がありません", "URL manquante dans la configuration du serveur MCP", "Falta la URL en la configuración del servidor MCP", "Falta a URL na configuração do servidor MCP", "URL fehlt in der MCP-Serverkonfiguration", "URL mancante nella configurazione del server MCP", "MCP 服务器配置缺少 URL", "MCP 서버 설정에 URL이 없습니다"],
		"No MCP servers configured": ["MCP サーバーが設定されていません", "Aucun serveur MCP configuré", "No hay servidores MCP configurados", "Nenhum servidor MCP configurado", "Keine MCP-Server konfiguriert", "Nessun server MCP configurato", "未配置 MCP 服务器", "설정된 MCP 서버가 없습니다"],
		"No response received from server. Please try again.": ["サーバーから応答がありません。もう一度お試しください。", "Aucune réponse du serveur. Réessayez.", "No se recibió respuesta del servidor. Inténtalo de nuevo.", "Nenhuma resposta do servidor. Tente novamente.", "Keine Antwort vom Server. Bitte erneut versuchen.", "Nessuna risposta dal server. Riprova.", "未收到服务器响应，请重试。", "서버 응답이 없습니다. 다시 시도하세요."],
		"Number of lines shown in tool output previews (last N lines). Only these previews and the final LLM response persist after the agentic loop completes.": ["ツール出力のプレビューに表示する行数（最後の N 行）です。エージェントループ終了後に残るのは、このプレビューと最終的な LLM の応答だけです。", "Nombre de lignes affichées dans les aperçus d'outils (N dernières). Seuls ces aperçus et la réponse finale sont conservés.", "Líneas mostradas en las vistas previas de herramientas (últimas N). Solo se conservan estas y la respuesta final.", "Linhas exibidas nas pré-visualizações (últimas N). Só elas e a resposta final permanecem.", "Zeilen in Tool-Vorschauen (letzte N). Nur diese und die finale Antwort bleiben erhalten.", "Righe mostrate nelle anteprime degli strumenti (ultime N). Restano solo queste e la risposta finale.", "工具输出预览中显示的行数（最后 N 行）。智能体循环结束后仅保留这些预览和最终的 LLM 响应。", "도구 출력 미리보기에 표시할 줄 수(마지막 N줄)입니다. 루프가 끝나면 이 미리보기와 최종 응답만 남습니다."],
		"Open prompt picker": ["プロンプト選択を開く", "Ouvrir le sélecteur de prompts", "Abrir selector de prompts", "Abrir seletor de prompts", "Prompt-Auswahl öffnen", "Apri selettore di prompt", "打开提示词选择器", "프롬프트 선택 열기"],
		"Open resource picker": ["リソース選択を開く", "Ouvrir le sélecteur de ressources", "Abrir selector de recursos", "Abrir seletor de recursos", "Ressourcen-Auswahl öffnen", "Apri selettore di risorse", "打开资源选择器", "리소스 선택 열기"],
		"Search prompts...": ["プロンプトを検索...", "Rechercher des prompts...", "Buscar prompts...", "Pesquisar prompts...", "Prompts suchen...", "Cerca prompt...", "搜索提示词...", "프롬프트 검색..."],
		"Request timed out - the server took too long to respond": ["リクエストがタイムアウトしました - サーバーの応答に時間がかかりすぎています", "Délai dépassé - le serveur a mis trop de temps à répondre", "Tiempo agotado: el servidor tardó demasiado", "Tempo esgotado - o servidor demorou demais", "Zeitüberschreitung – Server antwortet zu langsam", "Timeout - il server ha impiegato troppo a rispondere", "请求超时 - 服务器响应时间过长", "요청 시간 초과 - 서버 응답이 너무 늦습니다"],
		"Resource": ["リソース", "Ressource", "Recurso", "Recurso", "Ressource", "Risorsa", "资源", "리소스"],
		"Resource content": ["リソースの内容", "Contenu de la ressource", "Contenido del recurso", "Conteúdo do recurso", "Ressourceninhalt", "Contenuto della risorsa", "资源内容", "리소스 내용"],
		"Resources": ["リソース", "Ressources", "Recursos", "Recursos", "Ressourcen", "Risorse", "资源", "리소스"],
		"Server": ["サーバー", "Serveur", "Servidor", "Servidor", "Server", "Server", "服务器", "서버"],
		"Server Error": ["サーバーエラー", "Erreur du serveur", "Error del servidor", "Erro do servidor", "Serverfehler", "Errore del server", "服务器错误", "서버 오류"],
		"Server error": ["サーバーエラー", "Erreur du serveur", "Error del servidor", "Erro do servidor", "Serverfehler", "Errore del server", "服务器错误", "서버 오류"],
		"Server endpoint not found": ["サーバーのエンドポイントが見つかりません", "Point de terminaison introuvable", "No se encontró el endpoint del servidor", "Endpoint do servidor não encontrado", "Server-Endpunkt nicht gefunden", "Endpoint del server non trovato", "未找到服务器端点", "서버 엔드포인트를 찾을 수 없습니다"],
		"Server error - check server logs": ["サーバーエラー - サーバーログを確認してください", "Erreur du serveur - consultez les journaux", "Error del servidor: revisa los registros", "Erro do servidor - verifique os logs", "Serverfehler – Serverprotokolle prüfen", "Errore del server - controlla i log", "服务器错误 - 请检查服务器日志", "서버 오류 - 서버 로그를 확인하세요"],
		"Server is not running or unreachable": ["サーバーが起動していないか、接続できません", "Le serveur ne tourne pas ou est injoignable", "El servidor no está en marcha o no es accesible", "O servidor não está em execução ou inacessível", "Server läuft nicht oder ist nicht erreichbar", "Il server non è attivo o non è raggiungibile", "服务器未运行或无法访问", "서버가 실행 중이 아니거나 접근할 수 없습니다"],
		"Server not found - check server address": ["サーバーが見つかりません - アドレスを確認してください", "Serveur introuvable - vérifiez l'adresse", "Servidor no encontrado: revisa la dirección", "Servidor não encontrado - verifique o endereço", "Server nicht gefunden – Adresse prüfen", "Server non trovato - controlla l'indirizzo", "未找到服务器 - 请检查地址", "서버를 찾을 수 없습니다 - 주소를 확인하세요"],
		"Server temporarily unavailable": ["サーバーが一時的に利用できません", "Serveur temporairement indisponible", "Servidor temporalmente no disponible", "Servidor temporariamente indisponível", "Server vorübergehend nicht verfügbar", "Server temporaneamente non disponibile", "服务器暂时不可用", "서버를 일시적으로 사용할 수 없습니다"],
		"The request did not receive a response from the server before timing out.": ["タイムアウトまでにサーバーから応答がありませんでした。", "La requête n'a pas reçu de réponse avant l'expiration du délai.", "La solicitud no recibió respuesta antes de agotarse el tiempo.", "A solicitação não recebeu resposta antes do tempo limite.", "Die Anfrage erhielt vor dem Timeout keine Antwort.", "La richiesta non ha ricevuto risposta prima del timeout.", "请求在超时前未收到服务器响应。", "시간 초과 전에 서버 응답을 받지 못했습니다."],
		"The server responded with an error message. Review the details below.": ["サーバーがエラーを返しました。下の詳細を確認してください。", "Le serveur a renvoyé une erreur. Voir les détails ci-dessous.", "El servidor respondió con un error. Revisa los detalles abajo.", "O servidor respondeu com um erro. Veja os detalhes abaixo.", "Der Server meldete einen Fehler. Details unten.", "Il server ha restituito un errore. Vedi i dettagli sotto.", "服务器返回了错误信息，请查看下方详情。", "서버가 오류를 반환했습니다. 아래 세부 정보를 확인하세요."],
		"Tool call": ["ツール呼び出し", "Appel d'outil", "Llamada a herramienta", "Chamada de ferramenta", "Tool-Aufruf", "Chiamata allo strumento", "工具调用", "도구 호출"],
		"Unable to connect to server - please check if the server is running": ["サーバーに接続できません - サーバーが起動しているか確認してください", "Connexion impossible - vérifiez que le serveur tourne", "No se puede conectar: comprueba que el servidor esté en marcha", "Não foi possível conectar - verifique se o servidor está em execução", "Keine Verbindung – läuft der Server?", "Impossibile connettersi - verifica che il server sia attivo", "无法连接服务器 - 请检查服务器是否在运行", "서버에 연결할 수 없습니다 - 서버가 실행 중인지 확인하세요"],
		"Unknown server error": ["不明なサーバーエラー", "Erreur serveur inconnue", "Error desconocido del servidor", "Erro desconhecido do servidor", "Unbekannter Serverfehler", "Errore sconosciuto del server", "未知服务器错误", "알 수 없는 서버 오류"],
		"Usage statistics for MCP servers. Tracks how many times tools from each server have been used.": ["MCP サーバーの利用統計です。各サーバーのツールが使われた回数を記録します。", "Statistiques d'utilisation des serveurs MCP (nombre d'utilisations des outils).", "Estadísticas de uso de servidores MCP (veces que se usaron sus herramientas).", "Estatísticas de uso dos servidores MCP (quantas vezes as ferramentas foram usadas).", "Nutzungsstatistik der MCP-Server (wie oft Tools verwendet wurden).", "Statistiche d'uso dei server MCP (quante volte sono stati usati gli strumenti).", "MCP 服务器的使用统计，记录各服务器工具的使用次数。", "MCP 서버 사용 통계입니다. 각 서버의 도구 사용 횟수를 기록합니다."],
		"WebSocket connection closed": ["WebSocket 接続が閉じられました", "Connexion WebSocket fermée", "Conexión WebSocket cerrada", "Conexão WebSocket encerrada", "WebSocket-Verbindung geschlossen", "Connessione WebSocket chiusa", "WebSocket 连接已关闭", "WebSocket 연결이 닫혔습니다"],
		"WebSocket transport is not supported when using CORS proxy. Use HTTP transport instead.": ["CORS プロキシ使用時は WebSocket 通信に対応していません。HTTP を使用してください。", "Le transport WebSocket n'est pas pris en charge avec le proxy CORS. Utilisez HTTP.", "WebSocket no es compatible con el proxy CORS. Usa HTTP.", "WebSocket não é suportado com proxy CORS. Use HTTP.", "WebSocket wird mit CORS-Proxy nicht unterstützt. Bitte HTTP verwenden.", "WebSocket non è supportato con il proxy CORS. Usa HTTP.", "使用 CORS 代理时不支持 WebSocket 传输，请改用 HTTP。", "CORS 프록시 사용 시 WebSocket은 지원되지 않습니다. HTTP를 사용하세요."],
		"Inject custom system message at the beginning of the conversation": ["会話の先頭にカスタムのシステムメッセージを挿入", "Insérer un message système personnalisé au début", "Insertar un mensaje del sistema al inicio", "Inserir mensagem do sistema no início", "Eigene Systemnachricht am Anfang einfügen", "Inserisci un messaggio di sistema all'inizio", "在对话开头插入自定义系统消息", "대화 시작에 사용자 지정 시스템 메시지 삽입"],
		"Add custom system message for a new conversation": ["新しい会話にカスタムのシステムメッセージを追加", "Ajouter un message système pour une nouvelle conversation", "Añadir mensaje del sistema para una nueva conversación", "Adicionar mensagem do sistema para nova conversa", "Systemnachricht für neue Unterhaltung hinzufügen", "Aggiungi messaggio di sistema per una nuova conversazione", "为新对话添加自定义系统消息", "새 대화에 사용자 지정 시스템 메시지 추가"],
		"Attach files": ["ファイルを添付", "Joindre des fichiers", "Adjuntar archivos", "Anexar arquivos", "Dateien anhängen", "Allega file", "附加文件", "파일 첨부"],
		"No file selected": ["ファイルが選択されていません", "Aucun fichier choisi", "Ningún archivo seleccionado", "Nenhum arquivo selecionado", "Keine Datei ausgewählt", "Nessun file selezionato", "未选择文件", "선택한 파일이 없습니다"],
		"Invalid file format": ["ファイル形式が正しくありません", "Format de fichier invalide", "Formato de archivo no válido", "Formato de arquivo inválido", "Ungültiges Dateiformat", "Formato file non valido", "文件格式无效", "잘못된 파일 형식"],
		"Binary content": ["バイナリデータ", "Contenu binaire", "Contenido binario", "Conteúdo binário", "Binärer Inhalt", "Contenuto binario", "二进制内容", "바이너리 내용"],
		"System message": ["システムメッセージ", "Message système", "Mensaje del sistema", "Mensagem do sistema", "Systemnachricht", "Messaggio di sistema", "系统消息", "시스템 메시지"],
		"Untitled conversation": ["無題の会話", "Conversation sans titre", "Conversación sin título", "Conversa sem título", "Unbenannte Unterhaltung", "Conversazione senza titolo", "未命名对话", "제목 없는 대화"],
		"Previous message version": ["前のメッセージのバージョン", "Version précédente du message", "Versión anterior del mensaje", "Versão anterior da mensagem", "Vorherige Nachrichtenversion", "Versione precedente del messaggio", "上一个消息版本", "이전 메시지 버전"],
		"Next message version": ["次のメッセージのバージョン", "Version suivante du message", "Versión siguiente del mensaje", "Próxima versão da mensagem", "Nächste Nachrichtenversion", "Versione successiva del messaggio", "下一个消息版本", "다음 메시지 버전"],
		"Start recording": ["録音を開始", "Démarrer l'enregistrement", "Iniciar grabación", "Iniciar gravação", "Aufnahme starten", "Avvia registrazione", "开始录音", "녹음 시작"],
		"Stop recording": ["録音を停止", "Arrêter l'enregistrement", "Detener grabación", "Parar gravação", "Aufnahme stoppen", "Interrompi registrazione", "停止录音", "녹음 중지"],
		"Clear search": ["検索をクリア", "Effacer la recherche", "Borrar búsqueda", "Limpar pesquisa", "Suche löschen", "Cancella ricerca", "清除搜索", "검색 지우기"],
		"Remove item": ["項目を削除", "Retirer l'élément", "Quitar elemento", "Remover item", "Element entfernen", "Rimuovi elemento", "移除项目", "항목 제거"],
		"Update": ["更新", "Mettre à jour", "Actualizar", "Atualizar", "Aktualisieren", "Aggiorna", "更新", "업데이트"],
		"Open website": ["Web サイトを開く", "Ouvrir le site", "Abrir sitio web", "Abrir site", "Website öffnen", "Apri sito web", "打开网站", "웹사이트 열기"],
		"Copy model name": ["モデル名をコピー", "Copier le nom du modèle", "Copiar nombre del modelo", "Copiar nome do modelo", "Modellnamen kopieren", "Copia nome modello", "复制模型名称", "모델 이름 복사"],
		"Copy model name to clipboard": ["モデル名をクリップボードにコピー", "Copier le nom du modèle", "Copiar nombre del modelo", "Copiar nome do modelo", "Modellnamen kopieren", "Copia nome modello negli appunti", "将模型名称复制到剪贴板", "모델 이름을 클립보드에 복사"],
		"Copy model path to clipboard": ["モデルのパスをクリップボードにコピー", "Copier le chemin du modèle", "Copiar ruta del modelo", "Copiar caminho do modelo", "Modellpfad kopieren", "Copia percorso modello negli appunti", "将模型路径复制到剪贴板", "모델 경로를 클립보드에 복사"],
		"Unknown": ["不明", "Inconnu", "Desconocido", "Desconhecido", "Unbekannt", "Sconosciuto", "未知", "알 수 없음"],
		"Code incomplete": ["コードが未完成です", "Code incomplet", "Código incompleto", "Código incompleto", "Code unvollständig", "Codice incompleto", "代码不完整", "코드가 완성되지 않았습니다"],
		"Vision": ["画像", "Vision", "Visión", "Visão", "Vision", "Visione", "视觉", "비전"],
		"Audio": ["音声", "Audio", "Audio", "Áudio", "Audio", "Audio", "音频", "오디오"],
		"PDF File": ["PDF ファイル", "Fichier PDF", "Archivo PDF", "Arquivo PDF", "PDF-Datei", "File PDF", "PDF 文件", "PDF 파일"],
		"Failed to authorize": ["認証に失敗しました", "Échec de l'autorisation", "No se pudo autorizar", "Falha na autorização", "Autorisierung fehlgeschlagen", "Autorizzazione non riuscita", "授权失败", "인증에 실패했습니다"],
		"Enabled": ["有効", "Activé", "Activado", "Ativado", "Aktiviert", "Attivato", "已启用", "사용"],
		"Scroll left": ["左へスクロール", "Défiler à gauche", "Desplazar a la izquierda", "Rolar para a esquerda", "Nach links scrollen", "Scorri a sinistra", "向左滚动", "왼쪽으로 스크롤"],
		"Scroll right": ["右へスクロール", "Défiler à droite", "Desplazar a la derecha", "Rolar para a direita", "Nach rechts scrollen", "Scorri a destra", "向右滚动", "오른쪽으로 스크롤"]
	};

	// Dynamic strings (numbers inside): [regex, [ja, fr, es, pt, de, it, zh, ko]] with $1.. groups.
	var P = [
		[/^Processing \((\d+)%\)$/, ["処理中 ($1%)", "Traitement ($1 %)", "Procesando ($1%)", "Processando ($1%)", "Verarbeitung ($1 %)", "Elaborazione ($1%)", "处理中（$1%）", "처리 중 ($1%)"]],
		[/^Processing (\d+)%$/, ["処理中 $1%", "Traitement $1 %", "Procesando $1%", "Processando $1%", "Verarbeitung $1 %", "Elaborazione $1%", "处理中 $1%", "처리 중 $1%"]],
		[/^Processing (\d+)% \(ETA: (\d+)s\)$/, ["処理中 $1%（残り約 $2 秒）", "Traitement $1 % (reste $2 s)", "Procesando $1% (faltan $2 s)", "Processando $1% (faltam $2 s)", "Verarbeitung $1 % (noch $2 s)", "Elaborazione $1% (mancano $2 s)", "处理中 $1%（约剩 $2 秒）", "처리 중 $1% (남은 시간 $2초)"]],
		[/^([\d.,]+) tokens$/, ["$1 トークン", "$1 jetons", "$1 tokens", "$1 tokens", "$1 Tokens", "$1 token", "$1 个令牌", "$1 토큰"]],
		[/^([\d.,]+) tokens\/s$/, ["$1 トークン/秒", "$1 jetons/s", "$1 tokens/s", "$1 tokens/s", "$1 Tokens/s", "$1 token/s", "$1 令牌/秒", "$1 토큰/초"]],
		[/^Delete (\d+) Messages$/, ["$1 件のメッセージを削除", "Supprimer $1 messages", "Eliminar $1 mensajes", "Excluir $1 mensagens", "$1 Nachrichten löschen", "Elimina $1 messaggi", "删除 $1 条消息", "메시지 $1개 삭제"]],
		[/^(\d+) tools available · Show details$/, ["$1 個のツールが利用可能 · 詳細を表示", "$1 outils disponibles · Détails", "$1 herramientas disponibles · Ver detalles", "$1 ferramentas disponíveis · Ver detalhes", "$1 Tools verfügbar · Details", "$1 strumenti disponibili · Dettagli", "$1 个工具可用 · 查看详情", "도구 $1개 사용 가능 · 자세히"]],
		[/^PDF file with (\d+) pages$/, ["$1 ページの PDF ファイル", "Fichier PDF de $1 pages", "Archivo PDF de $1 páginas", "Arquivo PDF com $1 páginas", "PDF-Datei mit $1 Seiten", "File PDF di $1 pagine", "$1 页的 PDF 文件", "$1페이지 PDF 파일"]],
		[/^(\d+) resources attached$/, ["$1 件のリソースを添付済み", "$1 ressources jointes", "$1 recursos adjuntos", "$1 recursos anexados", "$1 Ressourcen angehängt", "$1 risorse allegate", "已附加 $1 个资源", "리소스 $1개 첨부됨"]],
		[/^(\d+) calls$/, ["$1 回の呼び出し", "$1 appels", "$1 llamadas", "$1 chamadas", "$1 Aufrufe", "$1 chiamate", "$1 次调用", "$1회 호출"]],
		[/^(\d+) turns$/, ["$1 ターン", "$1 tours", "$1 turnos", "$1 turnos", "$1 Runden", "$1 turni", "$1 轮", "$1턴"]],
		[/^([\d.,]+) calls\/s$/, ["$1 回/秒", "$1 appels/s", "$1 llamadas/s", "$1 chamadas/s", "$1 Aufrufe/s", "$1 chiamate/s", "$1 次/秒", "$1회/초"]],
		[/^(\d+) args?$/, ["引数 $1 個", "$1 arg.", "$1 arg.", "$1 arg.", "$1 Arg.", "$1 arg.", "$1 个参数", "인수 $1개"]],
		[/^(\d+) of (\d+) selected$/, ["$2 件中 $1 件を選択", "$1 sur $2 sélectionnés", "$1 de $2 seleccionados", "$1 de $2 selecionados", "$1 von $2 ausgewählt", "$1 di $2 selezionati", "已选择 $1 / $2", "$2개 중 $1개 선택"]],
		[/^Connection established with (\d+) tools \((\d+)ms\)$/, ["接続しました（ツール $1 個、$2ms）", "Connecté avec $1 outils ($2 ms)", "Conectado con $1 herramientas ($2 ms)", "Conectado com $1 ferramentas ($2 ms)", "Verbunden mit $1 Tools ($2 ms)", "Connesso con $1 strumenti ($2 ms)", "已连接，共 $1 个工具（$2ms）", "연결됨: 도구 $1개 ($2ms)"]],
		[/^· Connected in (\d+)ms$/, ["· $1ms で接続", "· Connecté en $1 ms", "· Conectado en $1 ms", "· Conectado em $1 ms", "· Verbunden in $1 ms", "· Connesso in $1 ms", "· $1ms 内连接", "· $1ms 만에 연결"]],
		[/^Message version (\d+) of (\d+)$/, ["メッセージのバージョン $1 / $2", "Version $1 sur $2 du message", "Versión $1 de $2 del mensaje", "Versão $1 de $2 da mensagem", "Nachrichtenversion $1 von $2", "Versione $1 di $2 del messaggio", "消息版本 $1 / $2", "메시지 버전 $1 / $2"]],
		[/^Server: (.+)$/, ["サーバー: $1", "Serveur : $1", "Servidor: $1", "Servidor: $1", "Server: $1", "Server: $1", "服务器：$1", "서버: $1"]],
		[/^Priority: (.+)$/, ["優先度: $1", "Priorité : $1", "Prioridad: $1", "Prioridade: $1", "Priorität: $1", "Priorità: $1", "优先级：$1", "우선순위: $1"]],
		[/^Server "(.+)" is not connected$/, ["サーバー「$1」は接続されていません", "Le serveur « $1 » n'est pas connecté", "El servidor «$1» no está conectado", "O servidor \"$1\" não está conectado", "Server „$1“ ist nicht verbunden", "Il server \"$1\" non è connesso", "服务器“$1”未连接", "서버 \"$1\"이(가) 연결되지 않았습니다"]],
		[/^Tool "(.+)" execution failed on server "(.+)": (.*)$/, ["サーバー「$2」でツール「$1」の実行に失敗しました: $3", "Échec de l'outil « $1 » sur le serveur « $2 » : $3", "Falló la herramienta «$1» en el servidor «$2»: $3", "A ferramenta \"$1\" falhou no servidor \"$2\": $3", "Tool „$1“ auf Server „$2“ fehlgeschlagen: $3", "Lo strumento \"$1\" non è riuscito sul server \"$2\": $3", "服务器“$2”上的工具“$1”执行失败：$3", "서버 \"$2\"에서 도구 \"$1\" 실행 실패: $3"]],
		[/^Unknown tool: (.+)$/, ["不明なツール: $1", "Outil inconnu : $1", "Herramienta desconocida: $1", "Ferramenta desconhecida: $1", "Unbekanntes Tool: $1", "Strumento sconosciuto: $1", "未知工具：$1", "알 수 없는 도구: $1"]],
		[/^Automatically selected model: (.+) from last message$/, ["直前のメッセージのモデル $1 を自動選択しました", "Modèle $1 sélectionné d'après le dernier message", "Modelo $1 seleccionado según el último mensaje", "Modelo $1 selecionado pela última mensagem", "Modell $1 aus der letzten Nachricht gewählt", "Modello $1 selezionato dall'ultimo messaggio", "已根据上一条消息自动选择模型 $1", "마지막 메시지의 모델 $1을(를) 자동 선택했습니다"]]
	];

	function lookup(text) {
		if (langIndex < 0) return null;
		var a = D[text];
		if (a) {
			var v = a[langIndex];
			return (typeof v === 'string') ? v : null;
		}
		for (var i = 0; i < P.length; i++) {
			var m = P[i][0].exec(text);
			if (m) {
				return P[i][1][langIndex].replace(/\$(\d)/g, function (_, n) { return m[+n] || ''; });
			}
		}
		return null;
	}

	// Never touch chat content, code, or editable fields.
	var SKIP_SELECTOR = '.markdown-block, .markdown-user-content, .markdown-system-content, pre, code, textarea, ' +
		'[contenteditable], [contenteditable="true"], script, style, [data-app-ext]';
	var MESSAGE_SELECTOR = '[aria-label$="message with actions"]';

	function skipTextNode(node) {
		var el = node.parentElement;
		if (!el) return true;
		if (el.closest(SKIP_SELECTOR)) return true;
		// Inside a message bubble, plain-text content (user text, reasoning) is pre-wrapped; the
		// stats / labels / action controls around it are UI and get translated.
		if (el.closest(MESSAGE_SELECTOR) && el.closest('.whitespace-pre-wrap, [class*="prose"]')
				&& !el.closest('button, [role="button"], summary')) return true;
		return false;
	}

	function translateTextNode(node) {
		var raw = node.nodeValue;
		if (!raw) return;
		var trimmed = raw.trim();
		if (!trimmed || trimmed.length > 300) return;
		var tr = lookup(trimmed);
		if (tr === null || tr === trimmed) return;
		if (skipTextNode(node)) return;
		var lead = raw.match(/^\s*/)[0], trail = raw.match(/\s*$/)[0];
		node.nodeValue = lead + tr + trail;
	}

	var ATTRS = ['placeholder', 'title'];
	function translateAttrs(el) {
		if (!el.getAttribute || (el.closest && el.closest('[data-app-ext]'))) return;
		for (var i = 0; i < ATTRS.length; i++) {
			var v = el.getAttribute(ATTRS[i]);
			if (!v) continue;
			var tr = lookup(v.trim());
			if (tr !== null && tr !== v) el.setAttribute(ATTRS[i], tr);
		}
	}

	function translateTree(root) {
		if (!root) return;
		if (root.nodeType === 3) { translateTextNode(root); return; }
		if (root.nodeType !== 1) return;
		translateAttrs(root);
		var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
		var n;
		while ((n = walker.nextNode())) {
			if (n.nodeType === 3) translateTextNode(n);
			else translateAttrs(n);
		}
	}

	var observer = null;
	function startTranslation() {
		if (langIndex < 0 || observer || !document.body) return;
		translateTree(document.body);
		observer = new MutationObserver(function (mutations) {
			for (var i = 0; i < mutations.length; i++) {
				var m = mutations[i];
				if (m.type === 'characterData') translateTextNode(m.target);
				else if (m.type === 'attributes') translateAttrs(m.target);
				else for (var j = 0; j < m.addedNodes.length; j++) translateTree(m.addedNodes[j]);
			}
		});
		observer.observe(document.body, {
			childList: true, subtree: true, characterData: true,
			attributes: true, attributeFilter: ATTRS
		});
	}

	function init() {
		// Follow the app's display language; fall back to the browser language.
		origFetch('/api/ui/language', { cache: 'no-store' })
			.then(function (r) { return r.ok ? r.json() : null; })
			.then(function (j) { if (j && j.language) setLang(j.language); })
			.catch(function () {})
			.then(startTranslation);
	}

	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
	else init();
})();
