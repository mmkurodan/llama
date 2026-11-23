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
		'Image cannot be displayed': ['画像を表示できません', "Impossible d'afficher l'image", 'No se puede mostrar la imagen', 'Não é possível exibir a imagem', 'Bild kann nicht angezeigt werden', "Impossibile visualizzare l'immagine", '无法显示图片', '이미지를 표시할 수 없습니다']
	};

	function lookup(text) {
		if (langIndex < 0) return null;
		var a = D[text];
		if (!a) return null;
		var v = a[langIndex];
		return (typeof v === 'string') ? v : null;
	}

	// Never touch chat content, code, or editable fields.
	var SKIP_SELECTOR = '.markdown-block, .markdown-user-content, .markdown-system-content, pre, code, textarea, ' +
		'[contenteditable], [contenteditable="true"], script, style, [data-app-ext]';
	var MESSAGE_SELECTOR = '[aria-label$="message with actions"]';

	function skipTextNode(node) {
		var el = node.parentElement;
		if (!el) return true;
		if (el.closest(SKIP_SELECTOR)) return true;
		// Inside a message bubble only the action controls are UI; the rest is content.
		if (el.closest(MESSAGE_SELECTOR) && !el.closest('button, [role="button"], summary')) return true;
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
